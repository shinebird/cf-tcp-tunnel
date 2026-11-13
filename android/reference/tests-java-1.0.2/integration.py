#!/usr/bin/env python3
"""Real TCP/TLS/WebSocket tests of production core, not Android/Cloudflare tests."""
from __future__ import annotations
import asyncio,json,os,socket,ssl,struct,subprocess,tempfile
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
from websockets.legacy.server import serve,WebSocketServerProtocol
from websockets.legacy.client import connect
from websockets.exceptions import ConnectionClosed
from websockets.frames import OP_PING,OP_BINARY,OP_CONT
ROOT=Path(__file__).resolve().parents[1];REPORT=[]
class Protocol(WebSocketServerProtocol):
    native_pings=0
    async def read_frame(self,max_size):
        f=await super().read_frame(max_size)
        if f.opcode==OP_PING:self.native_pings+=1
        return f
class Fixture:
    def __init__(self,client_echo=False,probe='normal',registered=True):
        self.client_echo=client_echo;self.probe=probe;self.registered=registered
        self.agent=None;self.agents=[];self.clients={};self.next_id=1
        self.headers=[];self.probes=[];self.errors=[];self.agent_errors=[]
    async def handler(self,ws,path):
        q=parse_qs(urlsplit(path).query);is_agent=urlsplit(path).path=='/agent';sid=None
        self.headers.append((path,ws.request_headers['Host'],ws.request_headers.get('Authorization')))
        try:
            if is_agent:
                self.agent=ws;self.agents.append(ws)
                if self.registered:await ws.send(json.dumps({'type':'registered','generation':len(self.agents)}))
            elif self.client_echo:await ws.send('{"type":"ready"}')
            else:
                if self.agent is None:await ws.close(1011,'Agent offline');return
                sid=self.next_id;self.next_id+=1;self.clients[sid]=ws
                await self.agent.send(json.dumps({'type':'open','id':sid,'port':int(q['port'][0])}))
            async for msg in ws:
                if not is_agent:
                    if self.client_echo:await ws.send(msg)
                    elif isinstance(msg,bytes) and self.agent:await self.agent.send(struct.pack('!I',sid)+msg)
                    continue
                if isinstance(msg,str):
                    item=json.loads(msg);kind=item['type']
                    if kind=='ping':
                        self.probes.append(item)
                        if self.probe!='ignore':await ws.send(json.dumps({'type':'pong','nonce':item['nonce']+(1 if self.probe=='wrong' else 0)}))
                    elif kind=='ready' and item['id'] in self.clients:await self.clients[item['id']].send('{"type":"ready"}')
                    elif kind in ('close','error'):
                        if kind=='error':self.agent_errors.append(item)
                        cli=self.clients.pop(item['id'],None)
                        if cli:await cli.close(1011,'Remote closed')
                elif len(msg)>4:
                    target=self.clients.get(struct.unpack('!I',msg[:4])[0])
                    if target:await target.send(msg[4:])
        except ConnectionClosed:pass
        except Exception as e:self.errors.append(repr(e))
        finally:
            if is_agent and self.agent is ws:
                self.agent=None;cs=list(self.clients.values());self.clients.clear()
                for c in cs:await c.close(1011,'Agent disconnected')
            elif sid is not None:
                self.clients.pop(sid,None)
                if self.agent:
                    try:await self.agent.send(json.dumps({'type':'close','id':sid}))
                    except ConnectionClosed:pass
async def wait(pred,timeout=8):
    async def loop():
        while not pred():await asyncio.sleep(.025)
    await asyncio.wait_for(loop(),timeout)
def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
class Java:
    def __init__(self,p):self.p=p;self.logs=[];self.reader=asyncio.create_task(self.read())
    async def read(self):
        while line:=await self.p.stdout.readline():self.logs.append(line.decode(errors='replace').strip())
    async def stop(self):
        if self.p.returncode is None:
            self.p.stdin.close()
            try:await asyncio.wait_for(self.p.wait(),4)
            except asyncio.TimeoutError:self.p.kill();await self.p.wait();raise AssertionError('Stop exceeded 4 seconds')
        await self.reader
        if any('local-test-token' in s for s in self.logs):raise AssertionError('Token leaked to logs')
    async def ready(self,s):await wait(lambda:any(s in line for line in self.logs))
async def launch(port,role='client',remote=3389,local=None,probe=0,ack=1200,insecure=True,trust=None,host='localhost',ping=250,idle=1800):
    local=local or free_port();opts=[] if trust is None else [f'-Djavax.net.ssl.trustStore={trust}','-Djavax.net.ssl.trustStorePassword=changeit']
    args=['java','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',*opts,'-cp',str(ROOT/'.test-classes'),'uk.ayachi.cftunnel.core.Harness',role,f'wss://{host}:{port}',str(local),str(remote),'127.0.0.1',str(remote),str(insecure).lower(),str(ping),str(idle),str(probe),'750',str(ack)]
    p=await asyncio.create_subprocess_exec(*args,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT)
    j=Java(p)
    if role=='client':await j.ready('已监听')
    return j,local
async def echo_tcp(r,w):
    try:
        while b:=await r.read(32768):w.write(b);await w.drain()
    except (ConnectionError,asyncio.CancelledError):pass
    finally:
        w.close()
        try:await w.wait_closed()
        except Exception:pass
async def roundtrip(port,size=262144):
    r,w=await asyncio.open_connection('127.0.0.1',port);data=os.urandom(size)
    try:w.write(data);await w.drain();assert await asyncio.wait_for(r.readexactly(size),6)==data
    finally:w.close();await w.wait_closed()
async def remote_roundtrip(port,target,size=262144):
    async with connect(f'wss://localhost:{port}/connect?agent=main&port={target}',ssl=ssl._create_unverified_context(),ping_interval=None,compression=None,extra_headers={'Authorization':'Bearer local-test-token'}) as ws:
        assert json.loads(await asyncio.wait_for(ws.recv(),4))['type']=='ready';data=os.urandom(size)
        for off in range(0,size,32768):await ws.send(data[off:off+32768])
        got=b''
        while len(got)<size:got+=await asyncio.wait_for(ws.recv(),6)
        assert got==data
async def record(name,func):
    await func();REPORT.append(name);print('PASS',name,flush=True)
async def main():
    with tempfile.TemporaryDirectory() as temp:
        tmp=Path(temp);cert=tmp/'cert.pem';key=tmp/'key.pem';trust=tmp/'trust.p12'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-days','2'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['keytool','-importcert','-noprompt','-alias','local','-keystore',str(trust),'-storepass','changeit','-file',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);sni=[]
        tls.set_servername_callback(lambda sock,name,ctx:sni.append(name))
        async def client_success():
            peer=Fixture(client_echo=True)
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,create_protocol=Protocol,ping_interval=None,compression=None) as srv:
                port=srv.sockets[0].getsockname()[1];j,local=await launch(port,insecure=False,trust=trust)
                try:
                    await roundtrip(local,1048576);await asyncio.gather(*(roundtrip(local,131072) for _ in range(4)))
                    for _ in range(4):await roundtrip(local,4096)
                    assert not peer.errors,peer.errors
                    assert all(h==f'localhost:{port}' and a=='Bearer local-test-token' for _,h,a in peer.headers)
                    assert 'localhost' in sni
                finally:await j.stop()
        await record('Client: trusted TLS, IP override retains Host/SNI, 1MiB, 4 concurrent, repeated sessions',client_success)
        async def reject(host,store):
            peer=Fixture(client_echo=True)
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1],insecure=False,trust=store,host=host);r,w=await asyncio.open_connection('127.0.0.1',local)
                try:assert await asyncio.wait_for(r.read(1),6)==b'';await j.ready('SSLHandshakeException');assert not peer.headers
                finally:w.close();await w.wait_closed();await j.stop()
        await record('TLS: default rejects unknown CA',lambda:reject('localhost',None))
        await record('TLS: trusted CA still rejects wrong hostname',lambda:reject('wrong.example',trust))
        async def insecure_success():
            peer=Fixture(client_echo=True)
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1],host='wrong.example')
                try:await roundtrip(local,32768)
                finally:await j.stop()
        await record('TLS: explicit insecure option',insecure_success)
        async def agent_reconnect():
            echo=await asyncio.start_server(echo_tcp,'127.0.0.1',0);target=echo.sockets[0].getsockname()[1];peer=Fixture()
            try:
                async with serve(peer.handler,'127.0.0.1',0,ssl=tls,create_protocol=Protocol,ping_interval=None,compression=None) as srv:
                    port=srv.sockets[0].getsockname()[1];j,_=await launch(port,'agent',remote=target)
                    try:
                        await j.ready('已注册');await asyncio.gather(*(remote_roundtrip(port,target) for _ in range(4)));await wait(lambda:not peer.clients)
                        for n in range(3):
                            peer.agent.transport.abort();await wait(lambda:len(peer.agents)>=n+2 and peer.agent is not None)
                            await remote_roundtrip(port,target,32768)
                        assert not peer.errors,peer.errors
                    finally:await j.stop()
            finally:echo.close();await echo.wait_closed()
        await record('Agent: 4 clients + 3 reconnects accepting new clients',agent_reconnect)
        async def whitelist():
            peer=Fixture()
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,_=await launch(srv.sockets[0].getsockname()[1],'agent')
                try:
                    await j.ready('已注册');await peer.agent.send('{"type":"open","id":4294967295,"port":22}');await wait(lambda:peer.agent_errors)
                    assert peer.agent_errors[0]['id']==4294967295 and 'not allowed' in peer.agent_errors[0]['message']
                finally:await j.stop()
        await record('Agent: port allowlist and uint32 ID',whitelist)
        async def heartbeat(mode):
            peer=Fixture(probe=mode)
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,create_protocol=Protocol,ping_interval=None,compression=None) as srv:
                j,_=await launch(srv.sockets[0].getsockname()[1],'agent',probe=400 if mode!='native' else 0)
                try:
                    await j.ready('已注册')
                    if mode=='native':await asyncio.sleep(1.4);assert peer.agents[0].native_pings>=2 and not peer.probes and len(peer.agents)==1
                    elif mode=='normal':await wait(lambda:len(peer.probes)>=3);assert len(peer.agents)==1
                    else:await wait(lambda:len(peer.agents)>=2);assert any('业务心跳响应超时' in s for s in j.logs)
                finally:await j.stop()
        for m in ['native','normal','wrong','ignore']:await record('Keepalive: '+m,lambda m=m:heartbeat(m))
        async def no_registered():
            peer=Fixture(registered=False)
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,_=await launch(srv.sockets[0].getsockname()[1],'agent',ack=600)
                try:await wait(lambda:len(peer.agents)>=2);assert any('registered 超时' in s for s in j.logs)
                finally:await j.stop()
        await record('Agent: registration ACK deadline and reconnect',no_registered)
        async def replaced():
            peer=Fixture()
            async with serve(peer.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,_=await launch(srv.sockets[0].getsockname()[1],'agent')
                try:await j.ready('已注册');await peer.agent.close(1012,'Agent replaced');await asyncio.wait_for(j.p.wait(),4);assert len(peer.agents)==1
                finally:await j.stop()
        await record('Agent: replacement stops reconnect conflict',replaced)
        async def fragments():
            data=os.urandom(60000)
            async def handler(ws,path):
                await ws.send('{"type":"ready"}');await ws.recv();await ws.write_frame(False,OP_BINARY,data[:30000]);await ws.ping(b'middle');await ws.write_frame(True,OP_CONT,data[30000:]);await ws.close(1000,'done')
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1]);r,w=await asyncio.open_connection('127.0.0.1',local)
                try:w.write(b'go');await w.drain();assert await asyncio.wait_for(r.readexactly(len(data)),5)==data;assert await asyncio.wait_for(r.read(1),4)==b''
                finally:w.close();await w.wait_closed();await j.stop()
        await record('Frames: fragmentation, interleaved Ping, drain before Close',fragments)
        async def bad_frame():
            async def handler(ws,path):
                await ws.send('{"type":"ready"}');ws.transport.write(bytes([0x82,0x80,0,0,0,0]));await ws.wait_closed()
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1]);r,w=await asyncio.open_connection('127.0.0.1',local)
                try:assert await asyncio.wait_for(r.read(1),5)==b'';await j.ready('帧头无效')
                finally:w.close();await w.wait_closed();await j.stop()
        await record('Frames: reject masked server frame',bad_frame)
        async def no_ready():
            async def handler(ws,path):await ws.wait_closed()
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1],ack=600);r,w=await asyncio.open_connection('127.0.0.1',local)
                try:assert await asyncio.wait_for(r.read(1),4)==b'';await j.ready('ready 超时')
                finally:w.close();await w.wait_closed();await j.stop()
        await record('Client: ready ACK deadline',no_ready)
        async def stop_tls():
            accepted=[]
            async def handler(r,w):
                accepted.append(w)
                try:await r.read()
                finally:w.close()
            srv=await asyncio.start_server(handler,'127.0.0.1',0)
            try:j,_=await launch(srv.sockets[0].getsockname()[1],'agent');await wait(lambda:accepted);await j.stop()
            finally:
                for w in accepted:w.close()
                srv.close();await srv.wait_closed()
        await record('Stop: cancellation during blocked TLS handshake',stop_tls)
        async def rdp_probe():
            request=bytes.fromhex('03 00 00 13 0e e0 00 00 00 00 00 01 00 08 00 0b 00 00 00')
            response=bytes.fromhex('03 00 00 13 0e d0 00 00 00 00 00 02 00 08 00 02 00 00 00')
            async def handler(ws,path):
                await ws.send('{"type":"ready","id":17}')
                assert await asyncio.wait_for(ws.recv(),5)==request
                for part in [response[:2],response[2:7],response[7:]]:await ws.send(part)
                await ws.wait_closed()
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1])
                try:
                    j.p.stdin.write(b'probe\n');await j.p.stdin.drain();await j.ready('RDP_CHECK PASS')
                    for stage in ['LOCAL_ACCEPT','DNS_OK','TCP_OK','TLS_OK','WS_101','READY','TCP_FIRST_UP','WSS_FIRST_UP_SENT','WSS_FIRST_DOWN','TCP_FIRST_DOWN_WRITTEN']:
                        assert any(stage in line for line in j.logs),stage
                    await j.ready('SESSION_END')
                    assert any('TCP↑=19 WSS↑=19 WSS↓=19 TCP↓=19' in line for line in j.logs),j.logs
                finally:await j.stop()
        await record('RDP probe: WSS loopback, split confirmation, stages and delivered byte counters',rdp_probe)
        async def nested_tls():
            # Authenticated inner TLS over transparent outer WSS; not a CredSSP/Windows login test.
            target=await asyncio.start_server(echo_tcp,'127.0.0.1',0,ssl=tls)
            target_port=target.sockets[0].getsockname()[1]
            async def bridge(ws,path):
                reader,writer=await asyncio.open_connection('127.0.0.1',target_port)
                async def down():
                    while data:=await reader.read(32768):await ws.send(data)
                async def up():
                    async for data in ws:
                        assert isinstance(data,bytes);writer.write(data);await writer.drain()
                tasks=[]
                try:
                    await ws.send('{"type":"ready"}');tasks=[asyncio.create_task(up()),asyncio.create_task(down())]
                    done,pending=await asyncio.wait(tasks,return_when=asyncio.FIRST_COMPLETED)
                    for t in done:
                        try:t.result()
                        except ConnectionClosed:pass
                finally:
                    for task in tasks:task.cancel()
                    await asyncio.gather(*tasks,return_exceptions=True);writer.close()
                    try:await writer.wait_closed()
                    except (ssl.SSLError,ConnectionError):pass
            try:
                async with serve(bridge,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                    j,local=await launch(srv.sockets[0].getsockname()[1],insecure=False,trust=trust)
                    try:
                        inner=ssl.create_default_context(cafile=str(cert))
                        reader,writer=await asyncio.open_connection('127.0.0.1',local,ssl=inner,server_hostname='localhost')
                        try:
                            for size in [19,511,16384,65536,262144]:
                                data=os.urandom(size);writer.write(data);await writer.drain()
                                assert await asyncio.wait_for(reader.readexactly(size),8)==data
                        finally:
                            writer.close()
                            try:await writer.wait_closed()
                            except (ssl.SSLError,ConnectionError):pass
                    finally:await j.stop()
            finally:target.close();await target.wait_closed()
        await record('Client: nested TLS handshake and encrypted payloads over WSS',nested_tls)
        async def delayed_frames():
            payload=os.urandom(64)
            async def handler(ws,path):
                await ws.send('{"type":"ready"}');await ws.recv()
                ws.transport.write(bytes([0x82,len(payload)])+payload[:3]);await asyncio.sleep(1.3)
                ws.transport.write(payload[3:]);await ws.wait_closed()
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1],ping=3000,idle=6000)
                reader,writer=await asyncio.open_connection('127.0.0.1',local)
                try:
                    writer.write(b'go');await writer.drain();assert await asyncio.wait_for(reader.readexactly(64),5)==payload
                finally:writer.close();await writer.wait_closed();await j.stop()
        await record('Client: delayed partial WebSocket frame remains byte-exact',delayed_frames)
        async def idle_timeout():
            class SilentProtocol(WebSocketServerProtocol):
                async def pong(self,data=b''):pass
            async def handler(ws,path):await ws.send('{"type":"ready"}');await ws.wait_closed()
            async with serve(handler,'127.0.0.1',0,ssl=tls,create_protocol=SilentProtocol,ping_interval=None,compression=None) as srv:
                j,local=await launch(srv.sockets[0].getsockname()[1]);reader,writer=await asyncio.open_connection('127.0.0.1',local)
                try:assert await asyncio.wait_for(reader.read(1),5)==b'';await j.ready('WebSocket 接收超时')
                finally:writer.close();await writer.wait_closed();await j.stop()
        await record('Watchdog: no Pong; blocking TLS read is cancelled by raw socket close',idle_timeout)
    print(f'PASS ALL {len(REPORT)} integration scenarios',flush=True)
    (ROOT/'tests/last-integration-result.txt').write_text('\n'.join('PASS '+s for s in REPORT)+'\n',encoding='utf-8')
if __name__=='__main__':asyncio.run(main())
