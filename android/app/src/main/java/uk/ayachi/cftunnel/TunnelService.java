package uk.ayachi.cftunnel;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import uk.ayachi.cftunnel.core.*;

public final class TunnelService extends Service {
    public static final String START="uk.ayachi.cftunnel.START",STOP="uk.ayachi.cftunnel.STOP",DIAGNOSE="uk.ayachi.cftunnel.DIAGNOSE";
    private static final String CHANNEL="tcp_tunnel_status";
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile int generation;
    private NativeTunnel tunnel;private PowerManager.WakeLock wake;
    private long prevUp,prevDown,prevTime,prevUptime,lastHealth;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String networkBlocked="unknown";
    private final Runnable tick=new Runnable(){public void run(){
        NativeTunnel t=tunnel;if(t==null)return;
        t.refreshStats();
        long now=SystemClock.elapsedRealtime(),uptime=SystemClock.uptimeMillis(),elapsed=Math.max(1,now-prevTime);
        if(elapsed>6000)LiveState.log("SERVICE_TICK_GAP elapsedMs="+elapsed+"; awakeMs="+(uptime-prevUptime)+
            "; ui="+LiveState.uiVisible+"; "+BackgroundPolicy.diagnostics(TunnelService.this));
        prevUptime=uptime;
        if(now-lastHealth>=30000){
            lastHealth=now;
            LiveState.log("SERVICE_ALIVE; ui="+LiveState.uiVisible+"; wakeHeld="+(wake!=null&&wake.isHeld())+
                "; networkBlocked="+networkBlocked+"; up="+t.uploaded.get()+"; down="+t.downloaded.get()+
                "; "+BackgroundPolicy.diagnostics(TunnelService.this));
        }
        LiveState.up=t.uploaded.get();LiveState.down=t.downloaded.get();LiveState.sessions=t.sessions();
        LiveState.upRate=(LiveState.up-prevUp)*1000/elapsed;LiveState.downRate=(LiveState.down-prevDown)*1000/elapsed;
        prevUp=LiveState.up;prevDown=LiveState.down;prevTime=now;
        if(Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager.class).notify(10,notification());
        main.postDelayed(this,2000);
    }};
    @Override public void onCreate(){super.onCreate();
        NotificationChannel c=new NotificationChannel(CHANNEL,"TCP 隧道运行状态",NotificationManager.IMPORTANCE_LOW);
        c.setDescription("隧道角色、会话数、TCP 流量与停止操作");getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(DIAGNOSE.equals(intent.getAction())){
            if(tunnel!=null){logEnvironment();tunnel.diagnoseRdp();}else{LiveState.log("请先启动 Client 隧道");stopSelf();}
            return START_NOT_STICKY;
        }
        if(tunnel!=null)return START_NOT_STICKY;
        final int thisGeneration=++generation;
        LiveState.reset();LiveState.active=true;LiveState.status="正在启动";
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(10,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(10,notification());
            ConfigStore store=new ConfigStore(this);Config config=store.config();
            LiveState.agent=config.agent;
            LiveState.log("CF TCP Tunnel 1.1.1 Native Release; role="+(config.agent?"Agent":"Client")+"; agentId="+config.id);
            LiveState.log("Worker="+config.url+"; connectHost="+(config.connectHost.isEmpty()?"(Worker DNS)":config.connectHost)+"; verifyTLS="+!config.insecure);
            logEnvironment();
            acquireSessionWakeLock();
            observeNetwork();
            tunnel=new NativeTunnel(config,new NativeTunnel.Events(){
                public void log(String s){LiveState.log(s);}
                public void status(String s){if(generation==thisGeneration)LiveState.status=s;}
                public void stopped(){main.post(()->{if(generation==thisGeneration)stopSelf();});}
            });
            prevUp=prevDown=0;prevTime=lastHealth=SystemClock.elapsedRealtime();prevUptime=SystemClock.uptimeMillis();tunnel.start();main.post(tick);
        }catch(Throwable e){LiveState.log("启动失败: "+e.getClass().getSimpleName()+": "+e.getMessage());stopSelf();}
        return START_NOT_STICKY;
    }
    // The user explicitly starts/stops this persistent interactive tunnel.
    // Hold only for its service lifetime; release in onDestroy including failure.
    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireSessionWakeLock(){
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"cftunnel:session");
        wake.setReferenceCounted(false);wake.acquire();
        LiveState.log("CPU_WAKE_ACQUIRED; 隧道停止时释放；后台网络限制仍需用户解除");
    }
    private void observeNetwork(){
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(android.net.Network n){LiveState.log("NETWORK_AVAILABLE "+n);}
            @Override public void onLost(android.net.Network n){networkBlocked="unknown";LiveState.log("NETWORK_LOST "+n);}
            @Override public void onBlockedStatusChanged(android.net.Network n,boolean blocked){
                networkBlocked=String.valueOf(blocked);LiveState.log("NETWORK_BLOCKED="+blocked+"; network="+n);
            }
        };
        try{getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback,main);networkCallback=callback;}
        catch(RuntimeException e){LiveState.log("Network observer unavailable: "+e.getClass().getSimpleName());}
    }
    private void logEnvironment(){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
            PowerManager pm=getSystemService(PowerManager.class);
            LiveState.log("Android API="+Build.VERSION.SDK_INT+"; deviceIdle="+pm.isDeviceIdleMode()+
                "; "+BackgroundPolicy.diagnostics(this)+"; dataSaver="+cm.getRestrictBackgroundStatus());
            LiveState.log(caps==null?"Network: no active default network":"Network: wifi="+caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+
                " cell="+caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)+" vpn="+caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)+
                " validated="+caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }catch(Exception e){LiveState.log("读取网络状态失败: "+e.getClass().getSimpleName());}
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TunnelService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String speeds="↑ "+LiveState.size(LiveState.upRate)+"/s  ↓ "+LiveState.size(LiveState.downRate)+"/s  · "+LiveState.sessions+" 会话";
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("CF TCP Tunnel · "+LiveState.status)
                .setContentText(speeds).setStyle(new Notification.BigTextStyle().bigText(speeds+"\n累计 ↑ "+LiveState.size(LiveState.up)+"  ↓ "+LiveState.size(LiveState.down)))
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,"停止隧道",stop).build());
        if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    @Override public void onDestroy(){
        ++generation;
        main.removeCallbacksAndMessages(null);NativeTunnel t=tunnel;tunnel=null;
        try {
            if(t!=null){
                try{t.refreshStats();LiveState.up=t.uploaded.get();LiveState.down=t.downloaded.get();}
                finally{t.close();}
            }
        } finally {
        if(networkCallback!=null){
            try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
            networkCallback=null;
        }
        if(wake!=null&&wake.isHeld())wake.release();wake=null;
        LiveState.log("CPU_WAKE_RELEASED");
        LiveState.active=false;LiveState.sessions=0;LiveState.upRate=LiveState.downRate=0;LiveState.status="已停止";
        LiveState.log("停止请求已发送给 C++ 核心；实际清理完成以 NATIVE_STOPPED 日志为准");stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
        }
    }
    @Override public IBinder onBind(Intent i){return null;}
}
