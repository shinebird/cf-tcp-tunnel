#!/usr/bin/env python3
"""Host JVM -> JNI -> actual PC-derived Beast/Asio/OpenSSL core.
Not an Android APK, Android TLS provider, real RDP server, or Cloudflare test.
Requires --library-dir containing the HOST libcf_tunnel.so (never package it in APK).
"""
import argparse,asyncio,importlib.util,json,os,socket,ssl,struct,subprocess,tempfile
from pathlib import Path
from websockets.legacy.server import serve
from websockets.legacy.client import connect
from websockets.frames import OP_BINARY,OP_CONT,OP_PING
from websockets.exceptions import ConnectionClosed
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('fixture',ROOT/'reference/tests-java-1.0.2/integration.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
Fixture,wait,free_port,echo_tcp,roundtrip,remote_roundtrip = (fixture.Fixture,fixture.wait,fixture.free_port,fixture.echo_tcp,fixture.roundtrip,fixture.remote_roundtrip)
REPORT=[]
class Process:
    def __init__(self,p):self.p=p;self.logs=[];self.reader=asyncio.create_task(self.read())
    async def read(self):
        while line:=await self.p.stdout.readline():self.logs.append(line.decode(errors='replace').strip())
    async def ready(self,text,timeout=10):await wait(lambda:any(text in x for x in self.logs),timeout)
    async def command(self,text):self.p.stdin.write((text+'\n').encode());await self.p.stdin.drain()
    async def stop(self):
        if self.p.returncode is None:
            self.p.stdin.close()
            try:await asyncio.wait_for(self.p.wait(),5)
            except asyncio.TimeoutError:self.p.kill();await self.p.wait();raise AssertionError('Native stop timed out')
        await self.reader
        assert self.p.returncode==0,'\n'.join(self.logs)
        assert not any('local-test-token' in x for x in self.logs),'Token leaked'
async def launch(lib,port,mode='client',remote=3389,local=None,trust=None,host='localhost',insecure=True):
    local=local or free_port();args=['java',f'-Djava.library.path={lib}','-Dfile.encoding=UTF-8']
    if trust:args += [f'-Djavax.net.ssl.trustStore={trust}','-Djavax.net.ssl.trustStorePassword=changeit']
    args += ['-cp',str(ROOT/'.test-classes'),'uk.ayachi.cftunnel.core.NativeHarness',mode,f'wss://{host}:{port}',str(local),str(remote),str(remote),str(insecure).lower()]
    p=await asyncio.create_subprocess_exec(*args,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT)
    j=Process(p)
    if mode=='client':await j.ready('Listening on ')
    return j,local
async def record(name,fn):
    await fn();REPORT.append(name);print('PASS',name,flush=True)
async def main(lib):
    with tempfile.TemporaryDirectory() as tmp:
        tmp=Path(tmp);cert=tmp/'cert.pem';key=tmp/'key.pem';trust=tmp/'trust.p12'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-days','2'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['keytool','-importcert','-noprompt','-alias','test','-keystore',str(trust),'-storepass','changeit','-file',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);sni=[]
        tls.set_servername_callback(lambda sock,host,ctx:sni.append(host))
        async def client_data():
            f=Fixture(client_echo=True)
            async with serve(f.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as server:
                j,local=await launch(lib,server.sockets[0].getsockname()[1],trust=trust,insecure=False)
                try:
                    await roundtrip(local,1024*1024)
                    await asyncio.gather(*(roundtrip(local,262144) for _ in range(4)))
                    assert all(h.startswith('localhost:') and t=='Bearer local-test-token' for _,h,t in f.headers)
                    assert 'localhost' in sni
                    await j.command('stats');await j.ready('STATS ')
                    values=[x for x in j.logs if x.startswith('STATS ')][-1].split()
                    assert int(values[1])>=2097152 and int(values[2])>=2097152,values
                finally:await j.stop()
        await record('JNI Client: 1MiB + four concurrent streams, exported CA trust, IP override preserves Host/SNI, byte counters',client_data)
        async def reject(trustfile,host):
            f=Fixture(client_echo=True)
            async with serve(f.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as server:
                j,local=await launch(lib,server.sockets[0].getsockname()[1],trust=trustfile,host=host,insecure=False)
                r,w=await asyncio.open_connection('127.0.0.1',local)
                try:
                    assert await asyncio.wait_for(r.read(1),8)==b''
                    await j.ready('certificate verify failed')
                    assert not f.headers
                finally:w.close();await w.wait_closed();await j.stop()
        await record('JNI TLS: rejects untrusted certificates by default',lambda:reject(None,'localhost'))
        await record('JNI TLS: rejects trusted certificate with wrong hostname',lambda:reject(trust,'wrong.local'))
        async def fragment():
            async def handler(ws,path):
                try:
                    await ws.send('{"type":"ready"}')
                    async for data in ws:
                        if not isinstance(data,bytes):continue
                        n=len(data)//2
                        await ws.write_frame(False,OP_BINARY,data[:n]);await ws.ping(b'between-fragments');await asyncio.sleep(.03)
                        await ws.write_frame(True,OP_CONT,data[n:])
                except ConnectionClosed:
                    pass  # PC core deliberately closes TCP on local EOF/stop.
            async with serve(handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(lib,srv.sockets[0].getsockname()[1])
                try:await roundtrip(local,131072)
                finally:await j.stop()
        await record('JNI Client: fragmented WebSocket binary messages with interleaved protocol Ping',fragment)
        async def full_pair():
            f=Fixture()
            async with await asyncio.start_server(echo_tcp,'127.0.0.1',0) as target:
                targetport=target.sockets[0].getsockname()[1]
                async with serve(f.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                    port=srv.sockets[0].getsockname()[1]
                    agent,_=await launch(lib,port,mode='agent',remote=targetport)
                    await agent.ready('registered with Worker')
                    client,local=await launch(lib,port,remote=targetport)
                    try:
                        await asyncio.gather(*(roundtrip(local,262144) for _ in range(4)))
                        for i in range(3):
                            n=len(f.agents);f.agent.transport.abort()
                            await wait(lambda:len(f.agents)>n,10)
                            await remote_roundtrip(port,targetport,65536)
                            await roundtrip(local,65536)
                        assert not f.errors,f.errors
                    finally:await client.stop();await agent.stop()
        await record('JNI Client + JNI Agent: multiplexing, 3 forced Agent reconnects and new sessions after every reconnect',full_pair)
        async def reuse():
            f=Fixture(client_echo=True)
            async with serve(f.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                j,local=await launch(lib,srv.sockets[0].getsockname()[1])
                try:
                    for i in range(4):
                        r,w=await asyncio.open_connection('127.0.0.1',local)
                        w.write(b'x');await w.drain();assert await r.readexactly(1)==b'x'
                        n=sum('Listening on ' in l for l in j.logs)
                        await j.command('restart')
                        assert await asyncio.wait_for(r.read(1),4)==b''
                        w.close();await w.wait_closed()
                        await wait(lambda:sum('Listening on ' in l for l in j.logs)>n,6)
                        await roundtrip(local,8192)
                finally:await j.stop()
        await record('JNI lifecycle: same process, same port, 4 stop/start cycles while a WebSocket read is blocked',reuse)
        async def nested_tls():
            # Standards-shaped initial 19-byte RDP negotiation, then a REAL
            # inner TLS handshake + HTTP payload. Not CredSSP or Windows RDP.
            async def target(r,w):
                try:
                    assert await r.readexactly(19)==bytes.fromhex('030000130ee00000000000010008000b000000')
                    w.write(bytes.fromhex('030000130ed000001234000200080008000000'));await w.drain()
                    await w.start_tls(tls)
                    req=await r.readuntil(b'\r\n\r\n');assert req.startswith(b'GET /')
                    payload=b'x'*524288
                    w.write(b'HTTP/1.1 200 OK\r\nContent-Length: 524288\r\n\r\n'+payload);await w.drain()
                    # Wait for the client to finish consuming before closing.
                    await r.read(1)
                finally:w.close();await w.wait_closed()
            f=Fixture()
            async with await asyncio.start_server(target,'127.0.0.1',0) as ts:
                tp=ts.sockets[0].getsockname()[1]
                async with serve(f.handler,'127.0.0.1',0,ssl=tls,ping_interval=None,compression=None) as srv:
                    port=srv.sockets[0].getsockname()[1]
                    agent,_=await launch(lib,port,mode='agent',remote=tp);await agent.ready('registered with Worker')
                    client,local=await launch(lib,port,remote=tp)
                    try:
                        r,w=await asyncio.open_connection('127.0.0.1',local)
                        w.write(bytes.fromhex('030000130ee00000000000010008000b000000'));await w.drain()
                        assert len(await asyncio.wait_for(r.readexactly(19),5))==19
                        inner=ssl.create_default_context(cafile=str(cert))
                        await w.start_tls(inner,server_hostname='localhost',ssl_handshake_timeout=10)
                        w.write(b'GET / HTTP/1.1\r\nHost: localhost\r\n\r\n');await w.drain()
                        header=await asyncio.wait_for(r.readuntil(b'\r\n\r\n'),8);assert b'200 OK' in header
                        assert await asyncio.wait_for(r.readexactly(524288),8)==b'x'*524288
                        w.close();await w.wait_closed()
                    finally:await client.stop();await agent.stop()
        await record('JNI pair: initial 19-byte RDP-shaped exchange, then real inner TLS and 512KiB HTTP body',nested_tls)
    print(f'{len(REPORT)} native JNI integration scenarios passed')
    (ROOT/'tests/native-test-result.txt').write_text('Host only: JVM -> JNI -> C++ Beast/Asio/OpenSSL\n'+ '\n'.join('PASS '+n for n in REPORT)+'\nNot tested: Android/NDK APK build, real phone, Cloudflare, real RDP/CredSSP.\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--library-dir',required=True);args=p.parse_args();asyncio.run(main(str(Path(args.library_dir).resolve())))
