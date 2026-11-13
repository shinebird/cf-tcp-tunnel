package uk.ayachi.cftunnel.core;
public final class Harness {
 public static void main(String[] a)throws Exception{
  // role url listen remote target ports insecure pingMs idleMs probeMs probeTimeoutMs ackMs
  Config c=new Config(a[0].equals("agent"),a[1],"main","local-test-token","127.0.0.1",Boolean.parseBoolean(a[6]),
    Integer.parseInt(a[2]),Integer.parseInt(a[3]),a[4],a[5],Long.parseLong(a[7]),Long.parseLong(a[8]),Long.parseLong(a[9]),Long.parseLong(a[10]),Long.parseLong(a[11]));
  java.util.concurrent.CountDownLatch stopped=new java.util.concurrent.CountDownLatch(1);
  Tunnel t=new Tunnel(c,new Tunnel.Events(){public void log(String s){System.out.println(s);}public void status(String s){System.out.println("STATUS "+s);}public void stopped(){stopped.countDown();}});
  Runtime.getRuntime().addShutdownHook(new Thread(t::close));t.start();
  Thread input=new Thread(()->{try{java.io.BufferedReader commands=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));String line;while((line=commands.readLine())!=null){if(line.equals("probe"))t.diagnoseRdp();}t.close();}catch(Exception ignored){}});input.setDaemon(true);input.start();stopped.await();
 }
}
