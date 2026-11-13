package uk.ayachi.cftunnel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import uk.ayachi.cftunnel.core.Config;

final class ConfigStore {
    private static final String ALIAS="cf_tcp_tunnel_token_v1";
    private final SharedPreferences prefs;
    ConfigStore(Context c){prefs=c.getSharedPreferences("tunnel",Context.MODE_PRIVATE);}
    String get(String name,String fallback){return prefs.getString(name,fallback);}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }
    private String encrypt(String value)throws Exception{
        if(value.isEmpty())return "";
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        return Base64.getEncoder().encodeToString(c.getIV())+":"+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    String token(String role)throws Exception{
        String sealed=get(role+"_token","");if(sealed.isEmpty())return "";
        String[] parts=sealed.split(":");if(parts.length!=2)throw new IllegalStateException("Token 存储格式错误");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
        return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
    }
    void save(Map<String,String> fields,String client,String agent)throws Exception{
        String ec=encrypt(client),ea=encrypt(agent);
        SharedPreferences.Editor e=prefs.edit();for(Map.Entry<String,String> x:fields.entrySet())e.putString(x.getKey(),x.getValue());
        e.putString("client_token",ec).putString("agent_token",ea).apply();
    }
    Config config()throws Exception{
        boolean agent=get("mode","client").equals("agent");
        return new Config(agent,get("url","wss://tcp-tunnel.example.com"),get("id","main"),token(agent?"agent":"client"),
                get("connect_host",""),get("insecure","false").equals("true"),Integer.parseInt(get("local_port","13389")),
                Integer.parseInt(get("remote_port","3389")),get("target_host","127.0.0.1"),get("ports","3389"));
    }
}
