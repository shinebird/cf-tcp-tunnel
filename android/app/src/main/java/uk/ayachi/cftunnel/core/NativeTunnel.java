package uk.ayachi.cftunnel.core;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Android UI/lifecycle adapter. All tunnel TLS/WS/TCP forwarding lives in C++. */
public final class NativeTunnel implements Closeable {
    static { System.loadLibrary("cf_tunnel"); }
    public interface Events { void log(String text); void status(String text); void stopped(); }
    // Avoid rebinding a port while a previous service generation is shutting down.
    private static final Object ONE_ENGINE = new Object();
    private final Object lock = new Object();
    private final Config config;
    private final Events events;
    private volatile long handle;
    private volatile Socket probeSocket;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean(), probing = new AtomicBoolean();
    public final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong();
    private volatile int sessionCount;
    public NativeTunnel(Config config, Events events) { this.config=config; this.events=events; }

    public void start() {
        if(!started.compareAndSet(false,true)) throw new IllegalStateException("Tunnel already started");
        new Thread(this::run, "CF-Cpp-io").start();
    }
    private String[] arguments() {
        List<String> a=new ArrayList<>();
        a.add("cf-tunnel");a.add(config.agent?"agent":"client");
        add(a,"--url",config.url.toASCIIString());add(a,"--agent-id",config.id);add(a,"--token",config.token);
        if(!config.connectHost.isEmpty()) add(a,"--connect-host",config.connectHost);
        if(config.insecure)a.add("--insecure-skip-verify");
        if(config.agent) {
            add(a,"--target-host",config.targetHost);
            StringBuilder ports=new StringBuilder();
            for(int port:config.allowedPorts){if(ports.length()>0)ports.append(',');ports.append(port);}
            add(a,"--ports",ports.toString());
        } else {
            add(a,"--listen-host","127.0.0.1");add(a,"--listen-port",String.valueOf(config.localPort));
            add(a,"--remote-port",String.valueOf(config.remotePort));
        }
        return a.toArray(new String[0]);
    }
    private static void add(List<String> a,String k,String v) {a.add(k);a.add(v);}
    private static String trustedCertificates() throws Exception {
        TrustManagerFactory f=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore)null);
        StringBuilder pem=new StringBuilder();
        for(TrustManager tm:f.getTrustManagers()) if(tm instanceof X509TrustManager) {
            for(X509Certificate x:((X509TrustManager)tm).getAcceptedIssuers()) {
                pem.append("-----BEGIN CERTIFICATE-----\n")
                   .append(Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(x.getEncoded()))
                   .append("\n-----END CERTIFICATE-----\n");
            }
        }
        if(pem.length()==0)throw new IllegalStateException("未能读取受信 CA；不会自动关闭证书验证");
        return pem.toString();
    }
    private void run() {
        try {
            synchronized(ONE_ENGINE) {
                if(closed.get())return;
                String ca=config.insecure?"":trustedCertificates();
                synchronized(lock) {
                    if(closed.get())return;
                    handle=nativeCreate(arguments(),ca);
                    if(handle==0)throw new IllegalStateException("Native core initialization failed");
                }
                try {
                    events.status(config.agent?"C++ Agent 正在注册":"C++ Client 正在监听");
                    int code=nativeRun(handle);
                    if(code!=0&&!closed.get())events.log("C++ 核心退出，code="+code);
                } finally {
                    synchronized(lock) {
                        refreshStats();long h=handle;handle=0;
                        nativeDestroy(h);sessionCount=0;
                    }
                }
            }
        } catch(Throwable e) {
            events.log("Native 启动/运行失败："+e.getClass().getSimpleName()+": "+e.getMessage());
        } finally {events.stopped();}
    }
    // Called only from the nativeRun worker thread; JNI carries logs, NOT RDP bytes.
    @SuppressWarnings("unused")
    private void onNativeLog(String text) {
        events.log("[C++] "+text);
        if(text.startsWith("Listening on "))events.status("C++ 已监听 127.0.0.1:"+config.localPort);
        else if(text.startsWith("Remote TCP session ready"))events.status("C++ 隧道就绪，透明转发中");
        else if(text.contains("registered with Worker"))events.status("C++ Agent 已注册 ["+config.id+"]");
        else if(text.startsWith("Agent reconnect:")||text.startsWith("Agent WebSocket disconnected:"))events.status("C++ Agent 重连中");
    }
    public void refreshStats() {
        synchronized(lock) {
            if(handle==0)return;
            long[] s=nativeStats(handle);
            if(s!=null&&s.length==3){uploaded.set(s[0]);downloaded.set(s[1]);sessionCount=(int)s[2];}
        }
    }
    public int sessions(){return sessionCount;}
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        Socket s=probeSocket;if(s!=null)try{s.close();}catch(Exception ignored){}
        synchronized(lock){if(handle!=0)nativeStop(handle);}
    }
    /** Diagnostic only. This plain TCP client goes through the native listener. */
    public void diagnoseRdp() {
        if(config.agent||closed.get()||!probing.compareAndSet(false,true))return;
        new Thread(()->{
            try(Socket s=new Socket()) {
                probeSocket=s;if(closed.get())return;
                events.log("RDP_CHECK BEGIN via C++ local listener (no credentials)");
                RdpProbe.Result result=RdpProbe.run(s,config.localPort);
                events.log("RDP_CHECK "+(result.accepted?"PASS: ":"REJECT: ")+result.description);
                events.log("RDP_CHECK: only initial negotiation; not a completed RDP login");
            } catch(Exception e){events.log("RDP_CHECK failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{probeSocket=null;probing.set(false);}
        },"CF-RDP-Probe").start();
    }
    private static native long nativeCreate(String[] args,String caPem);
    private native int nativeRun(long handle);
    private static native void nativeStop(long handle);
    private static native long[] nativeStats(long handle);
    private static native void nativeDestroy(long handle);
}
