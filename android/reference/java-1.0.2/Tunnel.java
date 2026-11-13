package uk.ayachi.cftunnel.core;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Android-free tunnel engine. A new AgentConnection owns fresh maps on EVERY reconnect. */
public final class Tunnel implements AutoCloseable {
    public interface Events { void log(String text);void status(String text);void stopped(); }
    public final AtomicLong uploaded=new AtomicLong(),downloaded=new AtomicLong();
    private final Config cfg;private final Events events;
    private final AtomicBoolean running=new AtomicBoolean(),reportedStop=new AtomicBoolean(),probing=new AtomicBoolean();
    private final AtomicLong sequence=new AtomicLong();
    private final Set<Wss> webSockets=ConcurrentHashMap.newKeySet();
    private final Set<Pipe> pipes=ConcurrentHashMap.newKeySet();
    private final Set<Socket> connecting=ConcurrentHashMap.newKeySet();
    private final Set<Client> clients=ConcurrentHashMap.newKeySet();
    private final Semaphore permits;
    private final ThreadPoolExecutor io;
    private final ScheduledExecutorService timers;
    private volatile ServerSocket server;
    private volatile Agent activeAgent;
    private static final AtomicInteger THREAD_ID=new AtomicInteger();
    public Tunnel(Config cfg,Events events){
        this.cfg=cfg;this.events=events;this.permits=new Semaphore(cfg.maxSessions);
        ThreadFactory f=r->{Thread t=new Thread(r,"tcp-tunnel-"+THREAD_ID.incrementAndGet());t.setDaemon(true);return t;};
        io=new ThreadPoolExecutor(0,96,30,TimeUnit.SECONDS,new SynchronousQueue<>(),f,new ThreadPoolExecutor.AbortPolicy());
        timers=Executors.newScheduledThreadPool(2,f);
    }
    public boolean running(){return running.get();}
    public int sessions(){Agent a=activeAgent;return cfg.agent?(a==null?0:a.targets.size()):clients.size();}
    private void log(String text){events.log(text.replace(cfg.token,"[TOKEN]"));}
    public boolean agentMode(){return cfg.agent;}
    public void diagnoseRdp(){
        if(!running.get()||cfg.agent){log("RDP_CHECK: 需要先启动 Client 模式");return;}
        if(!probing.compareAndSet(false,true)){log("RDP_CHECK: 已有自检进行中");return;}
        try{io.execute(()->{
            Socket probe=new Socket();connecting.add(probe);ScheduledFuture<?> deadline=null;
            try{
                if(!running.get())return;
                deadline=timers.schedule(()->{try{probe.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);
                log("RDP_CHECK BEGIN: 127.0.0.1:"+cfg.localPort+" → Agent="+cfg.id+":"+cfg.remotePort+"（不发送用户名或密码）");
                RdpProbe.Result result=RdpProbe.run(probe,cfg.localPort);
                log("RDP_CHECK "+(result.accepted?"PASS":"RESPONSE")+": "+result.description);
                log("RDP_CHECK: 仅验证初始协商往返，不代表 TLS/CredSSP 登录或桌面已成功；测试连接现在关闭。");
            }catch(Exception e){if(running.get())log("RDP_CHECK FAIL: "+error(e)+"；请结合此会话的 DNS/TCP/TLS/WS/READY 日志。");}
            finally{if(deadline!=null)deadline.cancel(false);connecting.remove(probe);try{probe.close();}catch(IOException ignored){}probing.set(false);}
        });}catch(RejectedExecutionException e){probing.set(false);log("RDP_CHECK: 后台任务繁忙或隧道已停止");}
    }
    public void start(){
        if(!running.compareAndSet(false,true))return;
        io.execute(()->{
            try{if(cfg.insecure)log("警告：已关闭服务器证书验证，仅用于排障");if(cfg.agent)agentLoop();else clientLoop();}
            catch(Exception e){if(running.get())log("隧道停止: "+error(e));}
            finally{close();if(reportedStop.compareAndSet(false,true))events.stopped();}
        });
    }
    private static String error(Exception e){return e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
    private void clientLoop()throws Exception{
        ServerSocket ss=new ServerSocket();server=ss;
        ss.setReuseAddress(true);ss.bind(new InetSocketAddress("127.0.0.1",cfg.localPort),32);
        if(!running.get())return;
        events.status("已监听 127.0.0.1:"+cfg.localPort+"；等待应用连接");
        log("Client → Agent="+cfg.id+" / TCP "+cfg.remotePort+"；本地监听不代表 Worker 已在线");
        while(running.get()){
            Socket tcp=ss.accept();tcp.setTcpNoDelay(true);tcp.setKeepAlive(true);
            if(!running.get()||!permits.tryAcquire()){log("LOCAL_REJECT: 会话数限制或正在停止");tcp.close();continue;}
            Client c=new Client(tcp);clients.add(c);
            c.trace("LOCAL_ACCEPT peer="+tcp.getRemoteSocketAddress()+" → "+cfg.id+":"+cfg.remotePort);
            events.status("本地应用已连接；正在建立 WSS");
            try{io.execute(c::run);}catch(RejectedExecutionException e){c.end(false);}
        }
    }
    private void agentLoop()throws InterruptedException{
        int delay=1;
        while(running.get()){
            Agent a=new Agent();activeAgent=a;events.status("正在连接 Agent ["+cfg.id+"]");
            webSockets.add(a.ws);a.ws.run();webSockets.remove(a.ws);a.stopTargets();activeAgent=null;
            if(a.replaced){log("同名 Agent 已接管；停止重连以避免互相顶替。请修改 Agent ID 后重新启动。");return;}
            if(!running.get())break;
            if(a.registered)delay=1;
            events.status("Agent 已断线，"+delay+" 秒后重连");
            Thread.sleep(delay*1000L+ThreadLocalRandom.current().nextInt(250));delay=Math.min(30,delay*2);
        }
    }
    private final class Client implements Wss.Listener {
        final long number=sequence.incrementAndGet(),acceptedAt=Wss.now();
        final AtomicBoolean ended=new AtomicBoolean(),firstWsSent=new AtomicBoolean(),firstTcpWritten=new AtomicBoolean();
        final AtomicLong tcpRead=new AtomicLong(),tcpWritten=new AtomicLong();
        final Pipe pipe;final Wss ws;
        volatile boolean ready,remoteClosed,firstDown,firstUp;volatile String phase="LOCAL_ACCEPT";
        volatile ScheduledFuture<?> timeout,summaryTask;
        Client(Socket tcp){
            ws=new Wss(cfg,this,io,timers);
            pipe=new Pipe(tcp,b->{
                tcpRead.addAndGet(b.length);
                if(!firstUp){firstUp=true;trace("TCP_FIRST_UP bytes="+b.length);}
                if(!ws.binary(b))end(false);else uploaded.addAndGet(b.length);
            },reason->{if(!ended.get())trace("TCP_END "+reason);end(false);},n->{
                tcpWritten.addAndGet(n);
                if(firstTcpWritten.compareAndSet(false,true))trace("TCP_FIRST_DOWN_WRITTEN bytes="+n);
            });
        }
        void trace(String text){log("[C"+number+"] "+text);}
        String summary(){return "phase="+phase+" TCP↑="+tcpRead.get()+" WSS↑="+ws.payloadSent.get()+
                " WSS↓="+ws.payloadReceived.get()+" TCP↓="+tcpWritten.get()+
                " queuedWSS="+ws.queuedBytes()+" queuedTCP="+pipe.incoming.bytes()+" ageMs="+(Wss.now()-acceptedAt);}
        void run(){
            webSockets.add(ws);
            try{
                summaryTask=timers.scheduleAtFixedRate(()->{if(!ended.get())trace("FLOW "+summary());},15,60,TimeUnit.SECONDS);
                if(ended.get()){summaryTask.cancel(false);return;}
                ws.run();
            }catch(RejectedExecutionException e){end(true);}finally{webSockets.remove(ws);}
        }
        public void progress(Wss ws,String step){phase=step;trace(step);}
        public void sentBinary(Wss ws,int bytes){if(firstWsSent.compareAndSet(false,true))trace("WSS_FIRST_UP_SENT bytes="+bytes);}
        public void opened(Wss ws){
            if(!running.get()||ended.get()){ws.close();return;}
            phase="WAIT_READY";trace("WAIT_READY");
            timeout=timers.schedule(()->{if(!ready)ws.abort("等待 ready 超时；请检查 Agent 和目标端口");},cfg.handshakeMs,TimeUnit.MILLISECONDS);
        }
        public void text(Wss ws,String text){
            Control c=Control.parse(text);
            if(c.type().equals("ready")&&!ready&&!ended.get()){
                ready=true;phase="READY";if(timeout!=null)timeout.cancel(false);
                trace("READY → "+cfg.id+":"+cfg.remotePort+" elapsedMs="+(Wss.now()-acceptedAt));
                events.status("远端 TCP 已就绪；等待应用数据");pipe.start();
            }else if(c.type().equals("error")){trace("远端错误: "+c.text("message"));ws.abort("目标 TCP 连接失败");}
        }
        public void binary(Wss ws,byte[] data){
            if(!ready||data.length==0||data.length>65536){ws.abort("ready 之前的数据或无效数据长度");return;}
            if(!firstDown){firstDown=true;trace("WSS_FIRST_DOWN bytes="+data.length);events.status("已收到远端 TCP 数据（未验证 RDP 登录）");}
            downloaded.addAndGet(data.length);if(!pipe.offer(data)){trace("本地 TCP 接收队列已满");end(false);}
        }
        public void closed(Wss ws,int code,String reason){
            remoteClosed=true;if(timeout!=null)timeout.cancel(false);
            trace("WS_CLOSED ["+code+"] "+reason+"; "+summary());
            if(ready)pipe.drain();else end(false);
        }
        void end(boolean immediate){
            if(!ended.compareAndSet(false,true))return;
            if(timeout!=null)timeout.cancel(false);if(summaryTask!=null)summaryTask.cancel(false);
            trace("SESSION_END "+summary());pipe.close();clients.remove(this);permits.release();
            if(running.get()&&clients.isEmpty())events.status("已监听 127.0.0.1:"+cfg.localPort+"；等待下一次连接（结果见日志）");
            if(immediate||!running.get())ws.close();else if(!remoteClosed)ws.gracefulClose();
        }
    }
    private final class Agent implements Wss.Listener {
        final Wss ws=new Wss(cfg,this,io,timers);
        final ConcurrentHashMap<Long,Target> targets=new ConcurrentHashMap<>();
        volatile boolean registered,replaced,closed;
        volatile long openedAt,lastProbe,pendingNonce,pendingAt;long nextNonce;
        ScheduledFuture<?> probe;
        public void progress(Wss ws,String step){log("[Agent] "+step);}
        public void opened(Wss ws){
            openedAt=Wss.now();lastProbe=openedAt;
            probe=timers.scheduleAtFixedRate(()->{
                if(closed||!running.get())return;long now=Wss.now();
                if(!registered){if(now-openedAt>cfg.handshakeMs)ws.abort("等待 registered 超时；Worker 必须为重连修复版或更新版本");return;}
                if(pendingNonce!=0&&now-pendingAt>cfg.probeTimeoutMs){ws.abort("业务心跳响应超时");return;}
                if(cfg.probeMs>0&&pendingNonce==0&&now-lastProbe>=cfg.probeMs){
                    long n=++nextNonce;pendingAt=now;lastProbe=now;pendingNonce=n;ws.text(Control.ping(n));
                }
            },100,Math.min(1000,Math.max(100,cfg.handshakeMs/4)),TimeUnit.MILLISECONDS);
        }
        public void text(Wss ws,String text){
            Control c=Control.parse(text);String type=c.type();
            if(type.equals("registered")){
                if(!registered){registered=true;events.status("Agent ["+cfg.id+"] 已注册");log("Agent registered；端口白名单="+cfg.allowedPorts);}
                return;
            }
            if(!registered){ws.abort("未注册时收到业务消息");return;}
            if(type.equals("pong")){if(c.number("nonce")==pendingNonce&&pendingNonce!=0)pendingNonce=0;return;}
            long id=c.number("id");if(id<1||id>0xffffffffL)return;
            if(type.equals("open")){
                long rawPort=c.number("port");
                if(rawPort<1||rawPort>65535||!cfg.allowedPorts.contains((int)rawPort)){ws.text(Control.error(id,"Port not allowed"));return;}
                if(targets.containsKey(id)){ws.abort("重复会话编号");return;}
                if(targets.size()>=cfg.maxSessions){ws.text(Control.error(id,"Too many sessions (Android limit 16)"));return;}
                Target t=new Target(this,id,(int)rawPort);targets.put(id,t);
                try{io.execute(t::connect);}catch(RejectedExecutionException e){t.end(true,"I/O worker limit");}
            }else if(type.equals("close")){Target t=targets.get(id);if(t!=null)t.end(false,null);}
        }
        public void binary(Wss ws,byte[] data){
            if(!registered||data.length<5||data.length>65540){ws.abort("无效 Agent 数据帧");return;}
            long id=Integer.toUnsignedLong(ByteBuffer.wrap(data,0,4).getInt());Target t=targets.get(id);
            if(t!=null){byte[] payload=Arrays.copyOfRange(data,4,data.length);
                if(!t.deliver(payload))t.end(true,"TCP receive queue full or not ready");else downloaded.addAndGet(payload.length);}
        }
        public void closed(Wss ws,int code,String reason){
            closed=true;replaced=code==1012&&reason.equals("Agent replaced");
            if(probe!=null)probe.cancel(false);stopTargets();log("Agent WebSocket 结束 ["+code+"]: "+reason);
        }
        void stopTargets(){for(Target t:targets.values())t.end(false,null);targets.clear();}
    }
    private final class Target {
        final Agent parent;final long id;final int port;final AtomicBoolean ended=new AtomicBoolean();
        volatile Socket socket;volatile Pipe pipe;volatile boolean ready;
        Target(Agent parent,long id,int port){this.parent=parent;this.id=id;this.port=port;}
        void connect(){
            try{
                InetAddress[] ips=InetAddress.getAllByName(cfg.targetHost);Exception last=null;
                for(int i=0;i<Math.min(ips.length,8);i++){
                    Socket s=new Socket();socket=s;connecting.add(s);
                    if(ended.get()||parent.closed||!running.get()){s.close();connecting.remove(s);return;}
                    try{s.setTcpNoDelay(true);s.setKeepAlive(true);s.connect(new InetSocketAddress(ips[i],port),5000);last=null;break;}
                    catch(Exception e){last=e;s.close();connecting.remove(s);}
                }
                if(last!=null)throw last;
                if(ended.get()||parent.closed||!running.get()){end(false,null);return;}
                Pipe p=new Pipe(socket,b->{
                    byte[] framed=new byte[4+b.length];ByteBuffer.wrap(framed).putInt((int)id).put(b);
                    if(!parent.ws.binary(framed))end(false,null);else uploaded.addAndGet(b.length);
                },reason->{if(!ended.get())log("Agent TCP "+id+" 结束: "+reason);end(true,null);},n->{});
                synchronized(this){
                    if(ended.get()||parent.closed){p.close();return;}
                    pipe=p;ready=true;
                    if(!parent.ws.text(Control.message("ready",id))){end(false,null);return;}
                    p.start();
                }
                connecting.remove(socket);log("Agent 会话 "+id+" → "+cfg.targetHost+":"+port);
            }catch(Exception e){if(!ended.get())log("目标 TCP 失败: "+error(e));end(true,"Target TCP connect failed");}
            finally{Socket s=socket;if(s!=null)connecting.remove(s);}
        }
        boolean deliver(byte[] b){Pipe p=pipe;return ready&&!ended.get()&&p!=null&&p.offer(b);}
        synchronized void end(boolean announce,String error){
            if(!ended.compareAndSet(false,true))return;
            parent.targets.remove(id,this);Socket s=socket;if(s!=null){connecting.remove(s);try{s.close();}catch(IOException ignored){}}
            Pipe p=pipe;if(p!=null)p.close();
            if(announce&&!parent.closed&&parent.ws.alive())parent.ws.text(error==null?Control.message("close",id):Control.error(id,error));
        }
    }
    private final class Pipe implements AutoCloseable {
        final Socket socket;final Consumer<byte[]> input;final Consumer<String> ended;final Consumer<Integer> written;
        final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
        final ByteQueue<byte[]> incoming=new ByteQueue<>(512*1024,128);
        volatile long writingAt;ScheduledFuture<?> watch;
        Pipe(Socket socket,Consumer<byte[]> input,Consumer<String> ended,Consumer<Integer> written){
            this.socket=socket;this.input=input;this.ended=ended;this.written=written;pipes.add(this);
        }
        void start(){
            if(closed.get()||!started.compareAndSet(false,true))return;
            try{
                watch=timers.scheduleAtFixedRate(()->{if(writingAt!=0&&Wss.now()-writingAt>30000)ended.accept("WRITE_TIMEOUT (30s)");},1,1,TimeUnit.SECONDS);
                io.execute(()->{
                    String reason="REMOTE_DRAIN_COMPLETE";
                    try {OutputStream out=socket.getOutputStream();byte[] b;
                        while(!closed.get()&&(b=incoming.take())!=null){writingAt=Wss.now();out.write(b);writingAt=0;written.accept(b.length);}
                    }catch(Exception e){reason="WRITE_ERROR "+error(e);}finally{writingAt=0;if(!closed.get())ended.accept(reason);}
                });
                io.execute(()->{
                    String reason="LOCAL_EOF";
                    try{InputStream in=socket.getInputStream();byte[] b=new byte[32768];int n;
                        while(!closed.get()&&(n=in.read(b))!=-1)if(n>0)input.accept(Arrays.copyOf(b,n));
                    }catch(Exception e){reason="READ_ERROR "+error(e);}finally{if(!closed.get())ended.accept(reason);}
                });
            }catch(RejectedExecutionException e){ended.accept("I/O worker limit or stopped");}
        }
        boolean offer(byte[] b){return !closed.get()&&incoming.offer(b,b.length);}
        void drain(){if(started.get())incoming.finish();else ended.accept("REMOTE_CLOSE_BEFORE_START");}
        public void close(){if(closed.compareAndSet(false,true)){
            incoming.abort();if(watch!=null)watch.cancel(false);try{socket.close();}catch(IOException ignored){}pipes.remove(this);
        }}
    }
    @Override public void close(){
        if(!running.getAndSet(false))return;
        ServerSocket ss=server;if(ss!=null)try{ss.close();}catch(IOException ignored){}
        for(Client c:clients)c.end(true);
        Agent a=activeAgent;if(a!=null){a.closed=true;a.stopTargets();}
        for(Wss ws:webSockets)ws.close();
        for(Socket s:connecting)try{s.close();}catch(IOException ignored){}
        for(Pipe p:pipes)p.close();
        timers.shutdownNow();io.shutdownNow();
    }
}
