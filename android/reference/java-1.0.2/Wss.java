package uk.ayachi.cftunnel.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** RFC 6455 client for the existing uncompressed binary tunnel protocol.
 * One reader + one serialized writer, bounded messages/queues and finite write deadlines.
 * No redirects, cookies, HTTP proxy, compression, or credentials in URLs.
 */
public final class Wss implements AutoCloseable {
    public interface Listener {
        default void progress(Wss ws,String stage) {}
        default void sentBinary(Wss ws,int bytes) {}
        void opened(Wss ws) throws Exception;
        void text(Wss ws,String text) throws Exception;
        void binary(Wss ws,byte[] bytes) throws Exception;
        void closed(Wss ws,int code,String reason);
    }
    static final int MAX_MESSAGE=65540;
    private static final SecureRandom RNG=new SecureRandom();
    private final Config cfg; private final Listener listener;
    private final ExecutorService io; private final ScheduledExecutorService timers;
    private final ByteQueue<Frame> out=new ByteQueue<>(2*1024*1024,256);
    private final AtomicBoolean stopped=new AtomicBoolean();
    private volatile Socket socket,rawSocket;
    private volatile String stage="NEW";
    private ScheduledFuture<?> openingDeadline;
    public final AtomicLong payloadSent=new AtomicLong(),payloadReceived=new AtomicLong();
    private volatile boolean connected,closing;
    private volatile String failure="连接关闭";
    private volatile long lastRx=now(),writeStarted;
    private ScheduledFuture<?> watchdog;
    private volatile int statusCode=1006;
    private static final class Frame {
        final int op;final byte[] bytes;final CountDownLatch sent=new CountDownLatch(1);
        Frame(int op,byte[] b){this.op=op;this.bytes=b;}
    }
    public Wss(Config cfg,Listener listener,ExecutorService io,ScheduledExecutorService timers){
        this.cfg=cfg;this.listener=listener;this.io=io;this.timers=timers;
    }
    public static long now(){return System.nanoTime()/1000000L;}
    public boolean alive(){return connected&&!stopped.get()&&!closing;}
    public int queuedBytes(){return out.bytes();}
    private void progress(String text){stage=text;listener.progress(this,clean(text));}
    public void run(){
        try {
            // A wall-clock deadline covers DNS + TCP + TLS + HTTP, not each read separately.
            openingDeadline=timers.schedule(()->abort("OPEN_TIMEOUT (60s), stage="+stage),60,TimeUnit.SECONDS);
            SSLSocket tls=connect();
            InputStream input=new BufferedInputStream(tls.getInputStream(),16384);
            OutputStream output=new BufferedOutputStream(tls.getOutputStream(),16384);
            handshake(input,output);
            if(stopped.get())throw new IOException("已停止");
            openingDeadline.cancel(false);
            // One blocking TLS reader; the watchdog closes the raw socket on timeout/stop.
            // Do not repeatedly time out reads in the middle of TLS records.
            tls.setSoTimeout(0); connected=true;lastRx=now();
            io.execute(()->writer(output));
            long[] lastPing={now()};
            watchdog=timers.scheduleAtFixedRate(()->{
                if(stopped.get())return;long n=now();
                if(n-lastRx>cfg.idleMs){abort("WebSocket 接收超时");return;}
                if(writeStarted!=0 && n-writeStarted>30000){abort("WebSocket 写入超时");return;}
                if(n-lastPing[0]>=cfg.pingMs){lastPing[0]=n;offer(9,ByteBuffer.allocate(8).putLong(n).array());}
            },100,Math.min(1000,Math.max(100,cfg.pingMs/2)),TimeUnit.MILLISECONDS);
            listener.opened(this);
            readLoop(input);
        } catch(Exception e) {
            if(!stopped.get())failure=clean("stage="+stage+"; "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));
        } finally {
            connected=false;if(openingDeadline!=null)openingDeadline.cancel(false);if(watchdog!=null)watchdog.cancel(false);
            terminateTransport();
            listener.closed(this,statusCode,failure);
        }
    }
    private String clean(String message){return message.replace(cfg.token,"[TOKEN]").replaceAll("[\\r\\n]"," ");}
    private synchronized void attach(Socket s,boolean raw)throws IOException{
        if(stopped.get()){s.close();throw new IOException("已停止");}
        socket=s;if(raw)rawSocket=s;
    }
    private void terminateTransport(){
        Socket raw,tls;
        synchronized(this){stopped.set(true);raw=rawSocket;tls=socket;}
        out.abort();
        // Closing the underlying TCP socket first unblocks a TLS read/write on another thread.
        if(raw!=null)try{raw.close();}catch(IOException ignored){}
        if(tls!=null&&tls!=raw)try{tls.close();}catch(IOException ignored){}
    }
    private SSLSocket connect()throws Exception{
        String dest=cfg.connectHost.isEmpty()?cfg.workerHost():cfg.connectHost;
        progress("DNS_BEGIN host="+dest);
        Future<InetAddress[]> lookup=io.submit(()->InetAddress.getAllByName(dest));
        InetAddress[] ips;
        try{ips=lookup.get(15,TimeUnit.SECONDS);}
        catch(TimeoutException e){throw new IOException("DNS_TIMEOUT (15s)",e);}
        finally{if(!lookup.isDone())lookup.cancel(true);}
        // Cancellation bounds this caller's wait; a platform-native resolver may finish later.
        progress("DNS_OK addresses="+Arrays.toString(ips));
        if(stopped.get())throw new IOException("已停止");
        Exception last=null;Socket raw=null;long deadline=now()+30000;
        for(int i=0;i<Math.min(ips.length,8)&&now()<deadline;i++){
            raw=new Socket();attach(raw,true);
            progress("TCP_CONNECT endpoint="+ips[i].getHostAddress()+":"+cfg.workerPort());
            try { raw.setTcpNoDelay(true);raw.setKeepAlive(true);
                raw.connect(new InetSocketAddress(ips[i],cfg.workerPort()),(int)Math.max(1,Math.min(10000,deadline-now())));break;
            }catch(Exception e){last=e;raw.close();raw=null;if(stopped.get())throw new IOException("已停止");}
        }
        if(raw==null)throw new IOException("连接 Worker 入口失败",last);
        progress("TCP_OK endpoint="+raw.getRemoteSocketAddress());
        SSLContext ssl=SSLContext.getInstance("TLS");
        TrustManager[] managers=null;
        if(cfg.insecure)managers=new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a){}
            public void checkServerTrusted(X509Certificate[] c,String a){}
        }};
        ssl.init(null,managers,RNG);
        SSLSocket tls=(SSLSocket)ssl.getSocketFactory().createSocket(raw,cfg.workerHost(),cfg.workerPort(),true);
        attach(tls,false);tls.setUseClientMode(true);tls.setSoTimeout(30000);
        SSLParameters params=tls.getSSLParameters();
        if(!cfg.insecure)params.setEndpointIdentificationAlgorithm("HTTPS");
        String host=cfg.workerHost();
        if(!host.contains(":")&&!host.matches("[0-9.]+"))params.setServerNames(Collections.singletonList(new SNIHostName(host)));
        List<String> protocols=new ArrayList<>();for(String p:tls.getSupportedProtocols())if(p.equals("TLSv1.2")||p.equals("TLSv1.3"))protocols.add(p);
        params.setProtocols(protocols.toArray(new String[0]));tls.setSSLParameters(params);
        progress("TLS_BEGIN sni="+host+" verify="+!cfg.insecure+" provider="+ssl.getProvider().getName());
        tls.startHandshake();
        progress("TLS_OK protocol="+tls.getSession().getProtocol());return tls;
    }
    private void handshake(InputStream in,OutputStream out)throws Exception{
        byte[] keyBytes=new byte[16];RNG.nextBytes(keyBytes);String key=Base64.getEncoder().encodeToString(keyBytes);
        String request="GET "+cfg.path()+" HTTP/1.1\r\nHost: "+cfg.authority()+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
                "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\nAuthorization: Bearer "+cfg.token+"\r\nUser-Agent: CF-TCP-Tunnel-Android/1.0.2\r\n\r\n";
        progress("WS_UPGRADE host="+cfg.authority()+" path="+cfg.path());
        out.write(request.getBytes(StandardCharsets.US_ASCII));out.flush();
        ByteArrayOutputStream header=new ByteArrayOutputStream();int tail=0;
        while(header.size()<16384){int b=in.read();if(b<0)throw new EOFException("WebSocket 握手 EOF");header.write(b);
            tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
        if(tail!=0x0d0a0d0a)throw new IOException("HTTP 响应头过长");
        String[] lines=new String(header.toByteArray(),StandardCharsets.ISO_8859_1).split("\r\n");
        String[] status=lines[0].split(" ",3);
        if(status.length<2||!status[0].equals("HTTP/1.1")||!status[1].equals("101")){
            String code=status.length>=2?status[1]:"invalid";
            throw new IOException("WebSocket HTTP "+code+"（401：Token；403：ID/端口；503：Agent离线/配置错误）");
        }
        Map<String,String> h=new HashMap<>();for(int i=1;i<lines.length;i++){
            int p=lines[i].indexOf(':');if(p<1)throw new IOException("HTTP 响应头格式错误");
            String k=lines[i].substring(0,p).trim().toLowerCase(Locale.ROOT),v=lines[i].substring(p+1).trim();
            if(h.containsKey(k)) h.put(k,h.get(k)+","+v);else h.put(k,v);
        }
        String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        if(!expected.equals(h.get("sec-websocket-accept"))||!"websocket".equalsIgnoreCase(h.get("upgrade"))
                ||!Arrays.asList(h.getOrDefault("connection","").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("upgrade")
                ||h.containsKey("sec-websocket-extensions")||h.containsKey("sec-websocket-protocol"))
            throw new IOException("WebSocket 升级响应验证失败");
        progress("WS_101");
    }
    public boolean text(String s){return offer(1,s.getBytes(StandardCharsets.UTF_8));}
    public boolean binary(byte[] b){return offer(2,b);}
    private boolean offer(int opcode,byte[] b){
        if(stopped.get()||closing)return false;
        if(b.length>MAX_MESSAGE || opcode>=8&&b.length>125){abort("发送消息超限");return false;}
        if(!out.offer(new Frame(opcode,b.clone()),b.length)){abort("WebSocket 发送队列已满");return false;}return true;
    }
    public void gracefulClose(){
        if(stopped.get()||closing)return;closing=true;
        Frame f=new Frame(8,new byte[]{3,(byte)232});
        if(!out.offer(f,2)){abort("关闭发送队列已满");return;}
        try{timers.schedule(()->abort("本地会话结束"),1000,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException e){abort("已停止");}
    }
    private void writer(OutputStream stream){
        try {Frame frame;while((frame=out.take())!=null&&!stopped.get()){
            writeStarted=now();writeFrame(stream,frame.op,frame.bytes);stream.flush();writeStarted=0;frame.sent.countDown();
            if(frame.op==2){payloadSent.addAndGet(frame.bytes.length);listener.sentBinary(this,frame.bytes.length);}
            if(frame.op==8)return;
        }}catch(Exception e){abort("WebSocket 写入失败: "+clean(e.getClass().getSimpleName()));}
        finally{writeStarted=0;}
    }
    static void writeFrame(OutputStream out,int op,byte[] payload)throws IOException{
        out.write(0x80|op);int n=payload.length;
        if(n<126)out.write(0x80|n);else if(n<=65535){out.write(0xfe);out.write(n>>>8);out.write(n);}
        else{out.write(0xff);for(int s=56;s>=0;s-=8)out.write((int)(((long)n)>>>s)&255);}
        byte[] mask=new byte[4];RNG.nextBytes(mask);out.write(mask);
        byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)(payload[i]^mask[i&3]);out.write(b);
    }
    private int octet(InputStream in)throws IOException{
        if(stopped.get())throw new IOException("WebSocket 已停止");
        int n=in.read();if(n<0)throw new EOFException("WebSocket EOF");lastRx=now();return n;
    }
    private byte[] bytes(InputStream in,int n)throws IOException{
        byte[] b=new byte[n];int pos=0;while(pos<n&&!stopped.get()){
            int k=in.read(b,pos,n-pos);if(k<0)throw new EOFException("WebSocket payload EOF");
            if(k>0){pos+=k;lastRx=now();}
        }
        if(pos!=n)throw new IOException("WebSocket 已停止");return b;
    }
    private static String utf8(byte[] b)throws CharacterCodingException{
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
    }
    private void readLoop(InputStream in)throws Exception{
        ByteArrayOutputStream fragments=null;int messageOp=0;
        while(!stopped.get()){
            int a=octet(in),b=octet(in);boolean fin=(a&0x80)!=0;int op=a&15;
            if((a&0x70)!=0||(b&0x80)!=0||!(op==0||op==1||op==2||op==8||op==9||op==10))throw new IOException("WebSocket 帧头无效");
            long length=b&127;
            if(length==126){length=((long)octet(in)<<8)|octet(in);if(length<126)throw new IOException("非规范帧长度");}
            else if(length==127){length=0;for(int i=0;i<8;i++){int v=octet(in);if(i==0&&v>=128)throw new IOException("帧长度溢出");length=(length<<8)|v;}
                if(length<65536)throw new IOException("非规范帧长度");}
            if(length>MAX_MESSAGE || op>=8&&(!fin||length>125))throw new IOException("WebSocket 帧过大/控制帧分片");
            byte[] data=bytes(in,(int)length);lastRx=now();
            if(op==9){offer(10,data);continue;}if(op==10)continue;
            if(op==8){
                if(data.length==1)throw new IOException("无效 Close 帧");
                int code=data.length>=2?((data[0]&255)<<8)|(data[1]&255):1000;
                if(code<1000||code>=5000||code==1004||code==1005||code==1006||code==1015)throw new IOException("无效 Close 状态码");
                String reason=data.length>2?utf8(Arrays.copyOfRange(data,2,data.length)):"远端关闭";
                statusCode=code;failure=clean(reason);
                if(!closing){closing=true;Frame f=new Frame(8,data);if(out.offer(f,data.length))f.sent.await(500,TimeUnit.MILLISECONDS);}
                return;
            }
            if(op==0){if(fragments==null)throw new IOException("孤立 continuation 帧");}
            else{if(fragments!=null)throw new IOException("交错数据消息");messageOp=op;fragments=new ByteArrayOutputStream();}
            if(fragments.size()+data.length>MAX_MESSAGE)throw new IOException("分片消息过大");fragments.write(data);
            if(fin){byte[] full=fragments.toByteArray();fragments=null;
                if(messageOp==1){if(full.length>8192)throw new IOException("控制文本过大");listener.text(this,utf8(full));}
                else {payloadReceived.addAndGet(full.length);listener.binary(this,full);}
            }
        }
    }
    public void abort(String reason){
        synchronized(this){if(stopped.get())return;failure=clean(reason);stopped.set(true);}
        terminateTransport();
    }
    @Override public void close(){abort("用户停止");}
}
