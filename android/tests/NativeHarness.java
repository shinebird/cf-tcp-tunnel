package uk.ayachi.cftunnel.core;
import java.util.concurrent.CountDownLatch;
public final class NativeHarness {
    static NativeTunnel t;
    static CountDownLatch done;
    static void launch(Config c) {
        done=new CountDownLatch(1);
        t=new NativeTunnel(c,new NativeTunnel.Events(){
            public void log(String s){System.out.println(s);}
            public void status(String s){System.out.println("STATUS "+s);}
            public void stopped(){System.out.println("HARNESS_STOPPED");done.countDown();}
        });
        t.start();
    }
    public static void main(String[] a)throws Exception {
        Config c=new Config(a[0].equals("agent"),a[1],"main","local-test-token","127.0.0.1",Boolean.parseBoolean(a[5]),
            Integer.parseInt(a[2]),Integer.parseInt(a[3]),"127.0.0.1",a[4]);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(t!=null)t.close();}));
        launch(c);
        java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String cmd;
        while((cmd=input.readLine())!=null) {
            if(cmd.equals("restart")) {
                t.close();if(!done.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("stop timeout");
                launch(c);
            } else if(cmd.equals("probe"))t.diagnoseRdp();
            else if(cmd.equals("stats")){t.refreshStats();System.out.println("STATS "+t.uploaded.get()+" "+t.downloaded.get()+" "+t.sessions());}
        }
        t.close();if(!done.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("stop timeout");
    }
}
