package uk.ayachi.cftunnel;
import java.text.SimpleDateFormat;
import java.util.*;

final class LiveState {
    static volatile boolean active,agent,uiVisible;
    static volatile String status="尚未启动";
    static volatile long up,down,upRate,downRate;
    static volatile int sessions;
    private static final ArrayDeque<String> LOG=new ArrayDeque<>();
    static synchronized void log(String s){
        android.util.Log.i("CFTunnel",s);
        LOG.add(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+s.replace('\r',' ').replace('\n',' '));
        while(LOG.size()>600)LOG.remove();
    }
    static synchronized String logs(){return String.join("\n",LOG);}
    static synchronized void reset(){up=down=upRate=downRate=0;sessions=0;LOG.clear();}
    static String size(long n){if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.ROOT,"%.1f KiB",n/1024.0);return String.format(Locale.ROOT,"%.1f MiB",n/1048576.0);}
}
