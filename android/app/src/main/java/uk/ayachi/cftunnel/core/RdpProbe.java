package uk.ayachi.cftunnel.core;

import java.io.*;
import java.net.*;
import java.util.Locale;

/** User-requested initial RDP negotiation through the active loopback tunnel.
 * No credentials, no authentication attempt, no certificate or NLA bypass.
 * Does not claim that a successful negotiation verifies the full RDP session.
 */
public final class RdpProbe {
    private RdpProbe(){}
    // TPKT + X.224 connection request + RDP_NEG_REQ (TLS | HYBRID | HYBRID_EX).
    public static byte[] request(){return new byte[]{3,0,0,19,14,(byte)0xe0,0,0,0,0,0,1,0,8,0,11,0,0,0};}
    public static final class Result {
        public final boolean accepted;
        public final String description;
        Result(boolean accepted,String description){this.accepted=accepted;this.description=description;}
    }
    public static Result run(Socket socket,int localPort)throws IOException{
        socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress("127.0.0.1",localPort),3000);
        socket.setSoTimeout(40000);
        socket.getOutputStream().write(request());socket.getOutputStream().flush();
        return parse(readPacket(socket.getInputStream()));
    }
    static byte[] readPacket(InputStream input)throws IOException{
        DataInputStream in=new DataInputStream(input);byte[] header=new byte[4];in.readFully(header);
        if(header[0]!=3||header[1]!=0)throw new IOException("收到的不是 RDP TPKT 响应，请检查远端端口/服务");
        int length=((header[2]&255)<<8)|(header[3]&255);
        if(length<11||length>8192)throw new IOException("RDP TPKT 长度无效");
        byte[] result=new byte[length];System.arraycopy(header,0,result,0,4);in.readFully(result,4,length-4);return result;
    }
    static Result parse(byte[] packet)throws IOException{
        if(packet.length<11||packet[0]!=3||packet[1]!=0||(((packet[2]&255)<<8)|(packet[3]&255))!=packet.length
                ||(packet[5]&255)!=0xd0||(packet[4]&255)+5!=packet.length)
            throw new IOException("无效 RDP X.224 Connection Confirm");
        if(packet.length==11)return new Result(true,"收到 RDP X.224 响应（没有安全协商字段）；隧道往返已验证");
        if(packet.length!=19||(packet[13]&255)!=8||packet[14]!=0)throw new IOException("RDP 协商结构长度无效");
        long code=(packet[15]&255L)|((packet[16]&255L)<<8)|((packet[17]&255L)<<16)|((packet[18]&255L)<<24);
        if(packet[11]==2){
            String name=code==0?"RDP":code==1?"TLS":code==2?"HYBRID / CredSSP":code==8?"HYBRID_EX / CredSSP":String.format(Locale.ROOT,"unknown 0x%08x",code);
            return new Result(true,"收到 RDP_NEG_RSP，selectedProtocol="+name+"；隧道初始双向通信正常");
        }
        if(packet[11]==3)return new Result(false,String.format(Locale.ROOT,"收到 RDP_NEG_FAILURE=0x%08x；服务端已回复，但拒绝本次初始协商（不是密码验证结果）",code));
        throw new IOException("未知 RDP 协商响应类型");
    }
}
