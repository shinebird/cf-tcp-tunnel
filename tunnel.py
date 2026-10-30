#!/usr/bin/env python3
"""Small TCP-over-WebSocket reverse tunnel. Python >= 3.10, websockets >= 14."""
import argparse
import asyncio
import contextlib
import json
import logging
import struct

from websockets.asyncio.client import connect

LOG = logging.getLogger('tunnel')
MAX_FRAME = 65536


def ws_url(base, path, port=None):
    base = base.rstrip('/')
    if not base.startswith(('wss://', 'ws://')):
        raise ValueError('Use wss:// URL (ws:// only for local development)')
    return f'{base}{path}' + (f'?port={port}' if port is not None else '')


async def send_control(ws, lock, **message):
    async with lock:
        await ws.send(json.dumps(message))


async def send_data(ws, lock, sid, payload):
    async with lock:
        await ws.send(struct.pack('!I', sid) + payload)


async def agent_once(args):
    sessions = {}
    send_lock = asyncio.Lock()
    allowed = set(args.ports)
    target_host = args.target_host
    async with connect(ws_url(args.url, '/agent'), additional_headers={'Authorization': f'Bearer {args.token}'},
                       max_size=MAX_FRAME + 4096, ping_interval=20, ping_timeout=20) as ws:
        LOG.info('Agent connected: %s; allowed TCP ports: %s', args.url, sorted(allowed))

        async def serve(sid, port, queue):
            writer = None
            try:
                reader, writer = await asyncio.wait_for(asyncio.open_connection(target_host, port), timeout=10)
                await send_control(ws, send_lock, type='ready', id=sid)

                async def to_target():
                    while True:
                        chunk = await queue.get()
                        if chunk is None:
                            return
                        writer.write(chunk)
                        await writer.drain()

                async def from_target():
                    while chunk := await reader.read(MAX_FRAME):
                        await send_data(ws, send_lock, sid, chunk)

                t1 = asyncio.create_task(to_target())
                t2 = asyncio.create_task(from_target())
                done, pending = await asyncio.wait((t1, t2), return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    task.result()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.warning('Session %d (port %d): %s', sid, port, exc)
                with contextlib.suppress(Exception):
                    await send_control(ws, send_lock, type='error', id=sid, message='Target connection failed')
            finally:
                if writer:
                    writer.close()
                    with contextlib.suppress(Exception):
                        await writer.wait_closed()
                sessions.pop(sid, None)
                with contextlib.suppress(Exception):
                    await send_control(ws, send_lock, type='close', id=sid)

        try:
            async for message in ws:
                if isinstance(message, str):
                    try:
                        obj = json.loads(message)
                        sid = obj['id']
                        kind = obj['type']
                        if not isinstance(sid, int) or not (0 < sid < 2**32):
                            continue
                    except (ValueError, KeyError, TypeError):
                        continue
                    if kind == 'open':
                        port = obj.get('port')
                        if type(port) is not int or port not in allowed or sid in sessions:
                            await send_control(ws, send_lock, type='error', id=sid, message='Port not allowed')
                            continue
                        queue = asyncio.Queue(maxsize=128)
                        task = asyncio.create_task(serve(sid, port, queue))
                        sessions[sid] = (queue, task)
                    elif kind == 'close':
                        session = sessions.get(sid)
                        if session:
                            session[1].cancel()
                elif len(message) >= 5:
                    sid = struct.unpack_from('!I', message)[0]
                    session = sessions.get(sid)
                    if session:
                        try:
                            session[0].put_nowait(message[4:])
                        except asyncio.QueueFull:
                            LOG.warning('Session %d overflow; closing', sid)
                            session[1].cancel()
        finally:
            tasks = [task for _, task in sessions.values()]
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)


async def agent(args):
    backoff = 1
    while True:
        try:
            await agent_once(args)
            backoff = 1
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            LOG.warning('Agent disconnected: %s', exc)
        await asyncio.sleep(backoff)
        backoff = min(30, backoff * 2)


async def proxy_connection(reader, writer, args):
    peer = writer.get_extra_info('peername')
    LOG.info('Incoming local TCP connection: %s', peer)
    try:
        async with connect(ws_url(args.url, '/connect', args.remote_port),
                           additional_headers={'Authorization': f'Bearer {args.token}'},
                           max_size=MAX_FRAME + 4096, ping_interval=20, ping_timeout=20) as ws:
            first = await asyncio.wait_for(ws.recv(), timeout=15)
            response = json.loads(first) if isinstance(first, str) else {}
            if response.get('type') != 'ready':
                raise RuntimeError(f'Remote not ready: {response}')

            async def local_to_ws():
                while chunk := await reader.read(MAX_FRAME):
                    await ws.send(chunk)

            async def ws_to_local():
                async for data in ws:
                    if not isinstance(data, bytes):
                        continue
                    writer.write(data)
                    await writer.drain()

            tasks = [asyncio.create_task(local_to_ws()), asyncio.create_task(ws_to_local())]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            for task in done:
                task.result()
    except Exception as exc:
        LOG.warning('Local TCP session ended: %s', exc)
    finally:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()


async def client(args):
    async def handler(r, w):
        await proxy_connection(r, w, args)
    server = await asyncio.start_server(handler, args.listen_host, args.listen_port)
    LOG.info('Listening on %s:%d -> private TCP port %d via %s',
             args.listen_host, args.listen_port, args.remote_port, args.url)
    async with server:
        await server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description='Cloudflare Workers TCP reverse tunnel')
    sub = parser.add_subparsers(dest='mode', required=True)
    agent_p = sub.add_parser('agent', help='Run inside the private network')
    client_p = sub.add_parser('client', help='Run on the remote accessing machine')
    for p in (agent_p, client_p):
        p.add_argument('--url', required=True, help='wss://YOUR-WORKER.workers.dev')
        p.add_argument('--token', required=True, help='Agent or client secret token')
    agent_p.add_argument('--target-host', default='127.0.0.1', help='Internal target IP address')
    agent_p.add_argument('--ports', required=True, type=lambda x: [int(p) for p in x.split(',')],
                         help='Comma-separated allowed target ports, e.g. 22,3389')
    client_p.add_argument('--remote-port', required=True, type=int)
    client_p.add_argument('--listen-host', default='127.0.0.1')
    client_p.add_argument('--listen-port', required=True, type=int)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    try:
        asyncio.run(agent(args) if args.mode == 'agent' else client(args))
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
