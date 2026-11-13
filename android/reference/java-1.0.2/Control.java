package uk.ayachi.cftunnel.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small strict parser for this protocol's flat JSON objects (no general JSON dependency). */
public final class Control {
    private final Map<String,Object> values;
    private Control(Map<String,Object> values) { this.values=values; }
    public String type() { return text("type"); }
    public String text(String k) { Object v=values.get(k); return v instanceof String?(String)v:""; }
    public long number(String k) { Object v=values.get(k); return v instanceof Long?(Long)v:-1; }
    public static Control parse(String text) {
        if(text.length()>8192) throw new IllegalArgumentException("控制消息过长");
        Parser p=new Parser(text); Map<String,Object> m=new LinkedHashMap<>(); p.ch('{');
        if(!p.peek('}')) do {
            String key=p.string(); p.ch(':'); Object v=p.value();
            if(m.containsKey(key)) throw new IllegalArgumentException("重复 JSON 键");
            m.put(key,v); if(m.size()>32)throw new IllegalArgumentException("JSON 键过多");
        } while(p.take(','));
        p.ch('}'); p.ws(); if(p.i!=text.length())throw new IllegalArgumentException("JSON 尾部数据");
        return new Control(m);
    }
    public static String message(String type,long id) { return "{\"type\":"+quote(type)+",\"id\":"+id+"}"; }
    public static String error(long id,String text) { return "{\"type\":\"error\",\"id\":"+id+",\"message\":"+quote(text)+"}"; }
    public static String ping(long nonce) { return "{\"type\":\"ping\",\"nonce\":"+nonce+"}"; }
    public static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) { char c=s.charAt(i);
            if(c=='"'||c=='\\') b.append('\\').append(c);
            else if(c<32)b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s){this.s=s;}
        void ws(){while(i<s.length()&&" \r\n\t".indexOf(s.charAt(i))>=0)i++;}
        boolean peek(char c){ws();return i<s.length()&&s.charAt(i)==c;}
        boolean take(char c){if(peek(c)){i++;return true;}return false;}
        void ch(char c){if(!take(c))throw new IllegalArgumentException("无效 JSON");}
        String string(){ch('"');StringBuilder b=new StringBuilder();
            while(i<s.length()) {char c=s.charAt(i++);if(c=='"')return b.toString();
                if(c<32)throw new IllegalArgumentException("JSON 控制字符");
                if(c=='\\'){if(i==s.length())break; c=s.charAt(i++);
                    switch(c){case '"':case '\\':case '/':break;case 'b':c='\b';break;case 'f':c='\f';break;
                        case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                        case 'u': if(i+4>s.length())throw new IllegalArgumentException("JSON unicode");
                            try{c=(char)Integer.parseInt(s.substring(i,i+4),16);}catch(Exception e){throw new IllegalArgumentException("JSON unicode");} i+=4;break;
                        default:throw new IllegalArgumentException("JSON 转义");}
                } b.append(c);
            }throw new IllegalArgumentException("JSON 字符串未结束");
        }
        Object value(){ws();if(peek('"'))return string();
            if(s.startsWith("true",i)){i+=4;return true;}if(s.startsWith("false",i)){i+=5;return false;}
            if(s.startsWith("null",i)){i+=4;return null;}int start=i;if(i<s.length()&&s.charAt(i)=='-')i++;
            int digits=i; while(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')i++;
            if(digits==i || i-digits>1&&s.charAt(digits)=='0')throw new IllegalArgumentException("JSON 整数");
            try{return Long.parseLong(s.substring(start,i));}catch(Exception e){throw new IllegalArgumentException("JSON 整数范围");}
        }
    }
}
