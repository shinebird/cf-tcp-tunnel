package uk.ayachi.cftunnel.core;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable snapshot; never persist or log this object's token in plaintext. */
public final class Config {
    public final boolean agent, insecure;
    public final URI url;
    public final String id, token, connectHost, targetHost;
    public final int localPort, remotePort;
    public final Set<Integer> allowedPorts;
    public final int maxSessions = 16;
    public final long pingMs, idleMs, probeMs, probeTimeoutMs, handshakeMs;

    public Config(boolean agent, String url, String id, String token, String connectHost,
                  boolean insecure, int localPort, int remotePort, String targetHost, String ports) {
        this(agent, url, id, token, connectHost, insecure, localPort, remotePort, targetHost, ports,
                45000, 90000, 600000, 90000, 15000);
    }
    // Explicit timings are useful for deterministic local integration tests.
    public Config(boolean agent, String url, String id, String token, String connectHost,
                  boolean insecure, int localPort, int remotePort, String targetHost, String ports,
                  long pingMs, long idleMs, long probeMs, long probeTimeoutMs, long handshakeMs) {
        this.agent = agent;
        try { this.url = new URI(url.trim()); }
        catch (Exception ex) { throw new IllegalArgumentException("Worker URL 格式错误"); }
        String path = this.url.getRawPath();
        if (!"wss".equalsIgnoreCase(this.url.getScheme()) || this.url.getHost() == null
                || this.url.getRawUserInfo() != null || this.url.getRawQuery() != null
                || this.url.getRawFragment() != null || !(path == null || path.isEmpty() || path.equals("/")))
            throw new IllegalArgumentException("使用 wss://Worker域名，可带端口，不要带路径、Token 或查询参数");
        if (this.url.getPort() != -1) port(this.url.getPort());
        this.id = id.trim().isEmpty() ? "main" : id.trim();
        if (!this.id.matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Agent ID：1–64 位小写字母、数字、_、-，首位字母或数字");
        if (token == null || token.isEmpty() || token.length() > 4096 || !token.matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException("Token 必须是非空 ASCII 字符串，不含空格或换行");
        this.token = token;
        this.connectHost = host(connectHost, true);
        this.targetHost = host(targetHost, false);
        port(localPort); port(remotePort);
        if (localPort < 1024) throw new IllegalArgumentException("手机监听端口请使用 1024–65535");
        this.localPort = localPort; this.remotePort = remotePort; this.insecure = insecure;
        Set<Integer> allow = new LinkedHashSet<>();
        for (String s : ports.split(",")) {
            try { int p = Integer.parseInt(s.trim()); port(p); allow.add(p); }
            catch (RuntimeException e) { throw new IllegalArgumentException("端口白名单应为逗号分隔的 1–65535 数字"); }
        }
        if (allow.isEmpty() || allow.size() > 64) throw new IllegalArgumentException("白名单需要 1–64 个端口");
        this.allowedPorts = Collections.unmodifiableSet(allow);
        if (pingMs < 100 || idleMs <= pingMs || probeMs < 0 || probeTimeoutMs < 100 || handshakeMs < 100)
            throw new IllegalArgumentException("无效超时参数");
        this.pingMs = pingMs; this.idleMs = idleMs; this.probeMs = probeMs;
        this.probeTimeoutMs = probeTimeoutMs; this.handshakeMs = handshakeMs;
    }
    static void port(int value) { if (value < 1 || value > 65535) throw new IllegalArgumentException("端口超出范围"); }
    static String host(String value, boolean emptyAllowed) {
        String s = value == null ? "" : value.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length()-1);
        if (s.isEmpty() && emptyAllowed) return s;
        if (s.isEmpty() || s.length()>253 || !s.matches("[A-Za-z0-9._:%-]+"))
            throw new IllegalArgumentException("主机只能是 IP / 域名，不要填协议、路径或端口");
        if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':'))
            throw new IllegalArgumentException("主机不要附加端口；IPv6 可使用方括号");
        return s;
    }
    public String workerHost() { String s=url.getHost(); return s.startsWith("[") ? s.substring(1,s.length()-1) : s; }
    public int workerPort() { return url.getPort() < 0 ? 443 : url.getPort(); }
    public String authority() { String s=workerHost(); if(s.contains(":"))s="["+s+"]"; return workerPort()==443?s:s+":"+workerPort(); }
    public String path() { return agent?"/agent?agent="+id : "/connect?agent="+id+"&port="+remotePort; }
}
