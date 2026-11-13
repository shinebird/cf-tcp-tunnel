package uk.ayachi.cftunnel.core;
import java.io.*;
import java.util.*;
public final class CoreTest {
 static int count;
 static void ok(boolean b){count++;if(!b)throw new AssertionError("assertion "+count);}
 static void bad(Runnable f){count++;try{f.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("expected failure "+count);}
 static Config cfg(String url,String id,String token,String host){return new Config(false,url,id,token,host,false,13389,3389,"127.0.0.1","22,3389");}
 public static void main(String[] args)throws Exception{
  Config c=cfg("wss://worker.example","main","local-test-token","1.2.3.4");
  ok(c.path().equals("/connect?agent=main&port=3389"));ok(c.authority().equals("worker.example"));ok(c.workerHost().equals("worker.example"));
  ok(c.allowedPorts.contains(3389));ok(c.workerPort()==443);ok(c.maxSessions==16);
  ok(cfg("wss://worker.example:8443/","","token","[2001:db8::1]").connectHost.equals("2001:db8::1"));
  ok(cfg("wss://worker.example:8443","a-b_1","token","edge.example").authority().equals("worker.example:8443"));
  for(String u:Arrays.asList("ws://worker.example","https://worker.example","wss://a:70000","wss://worker.example/path","wss://x@worker.example","wss://worker.example/?token=x","wss://worker.example/#x"))bad(()->cfg(u,"main","token",""));
  for(String id:Arrays.asList("UPPER","中文","../evil","_bad","a b"))bad(()->cfg("wss://worker.example",id,"token",""));
  for(String t:Arrays.asList("","a\rb","a\nb","a b","密码"))bad(()->cfg("wss://worker.example","main",t,""));
  for(String h:Arrays.asList("https://1.2.3.4","x:443","a/b","a b"))bad(()->cfg("wss://worker.example","main","token",h));
  Control ctrl=Control.parse("{\"type\":\"open\",\"id\":4294967295,\"port\":3389}");
  ok(ctrl.type().equals("open"));ok(ctrl.number("id")==4294967295L);ok(ctrl.number("port")==3389);ok(ctrl.number("none")==-1);
  ok(Control.parse(Control.error(1,"a\n\"b\\中")).text("message").equals("a\n\"b\\中"));
  ok(Control.parse("{\"type\":\"x\",\"flag\":false,\"x\":null,\"msg\":\"\\u4e2d\"}").text("msg").equals("中"));
  for(String s:Arrays.asList("","[]","{\"id\":1,\"id\":2}","{\"id\":01}","{\"id\":1.5}","{\"id\":1}garbage","{\"type\":\"abc}"))bad(()->Control.parse(s));
  ByteQueue<byte[]> q=new ByteQueue<>(5,2);ok(q.offer(new byte[3],3));ok(!q.offer(new byte[3],3));ok(q.offer(new byte[2],2));ok(!q.offer(new byte[0],0));
  ok(q.take().length==3);ok(q.bytes()==2);q.finish();ok(!q.offer(new byte[1],1));ok(q.take().length==2);ok(q.take()==null);
  q=new ByteQueue<>(5,2);q.offer(new byte[1],1);q.abort();ok(q.take()==null);ok(q.bytes()==0);
  for(int size:new int[]{0,1,125,126,127,65535,65536,65540}){
   byte[] b=new byte[size];new Random(4).nextBytes(b);ByteArrayOutputStream stream=new ByteArrayOutputStream();Wss.writeFrame(stream,2,b);
   byte[] f=stream.toByteArray();ok((f[0]&255)==0x82);ok((f[1]&128)!=0);
   int marker=f[1]&127,p=2;long n=marker;
   if(marker==126){n=((f[p++]&255)<<8)|(f[p++]&255);}else if(marker==127){n=0;for(int i=0;i<8;i++)n=(n<<8)|(f[p++]&255);}
   ok(n==size);byte[] mask=Arrays.copyOfRange(f,p,p+4);p+=4;byte[] restored=new byte[size];for(int i=0;i<size;i++)restored[i]=(byte)(f[p+i]^mask[i&3]);ok(Arrays.equals(restored,b));
  }
  byte[] request=RdpProbe.request();ok(request.length==19);ok(request[5]==(byte)0xe0);ok(request[15]==11);
  byte[] confirm={3,0,0,19,14,(byte)0xd0,0,0,0,0,0,2,0,8,0,2,0,0,0};
  ok(RdpProbe.parse(confirm).accepted);ok(RdpProbe.parse(confirm).description.contains("CredSSP"));
  byte[] failure=confirm.clone();failure[11]=3;failure[15]=5;ok(!RdpProbe.parse(failure).accepted);
  byte[] legacy={3,0,0,11,6,(byte)0xd0,0,0,0,0,0};ok(RdpProbe.parse(legacy).accepted);
  InputStream tinyReads=new FilterInputStream(new ByteArrayInputStream(confirm)){
    public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(1,len));}
  };ok(Arrays.equals(confirm,RdpProbe.readPacket(tinyReads)));
  for(int n:new int[]{0,3,10,18}){count++;try{RdpProbe.readPacket(new ByteArrayInputStream(Arrays.copyOf(confirm,n)));throw new AssertionError("short RDP accepted");}catch(IOException expected){}}
  for(int index:new int[]{0,1,3,4,5,11,13}){byte[] broken=confirm.clone();broken[index]=(byte)0xff;count++;
    try{RdpProbe.parse(broken);throw new AssertionError("bad RDP accepted index="+index);}catch(IOException expected){}}
  System.out.println("PASS "+count+" core assertions");
 }
}
