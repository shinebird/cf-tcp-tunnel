# 与现有 C++ / Worker 的线协议兼容

核对源文件：本会话 `cf-tcp-tunnel-hibernation-optimized.zip` 中 `src/index.js` 和 `cpp/src/main.cpp`，不是另一个 ECH Workers VPN 工程。

## 连接

- Agent: `GET /agent?agent=<id>`，`Authorization: Bearer <AGENT token>`。
- Client: `GET /connect?agent=<id>&port=<remote port>`，`Authorization: Bearer <CLIENT token>`。
- ID 缺省 `main`，正则 `[a-z0-9][a-z0-9_-]{0,63}`。
- Worker 地址根路径，WSS only，不跟随重定向以免转交凭据。
- IP / CNAME override 只影响 DNS/TCP 目标；HTTP Host、TLS SNI、TLS 证书主机名仍是 Worker URL 的域名。

## Agent

Worker 握手完成后先发送 `{"type":"registered","generation":...}`。
新 TCP 会话：Worker → Agent `{"type":"open","id":N,"port":P}`。
Agent 连接目标成功后 → Worker `{"type":"ready","id":N}`；失败发送 error。
Agent 双向数据：二进制 `uint32 big-endian session ID` + `1..65536 bytes TCP payload`。
结束：任意方向 `{"type":"close","id":N}`；错误 `{"type":"error","id":N,"message":"..."}`。
业务探测：Agent `{"type":"ping","nonce":N}`；Worker `{"type":"pong","nonce":N}`，只接受匹配响应。
同名 Agent 替换：close code 1012 + reason `Agent replaced`，本 App 不争抢重连。

## Client

Worker 在 Agent ready 后发送 `{"type":"ready"}`；在此之前不从本地 TCP 读取数据发送 Worker。
之后双向 WebSocket binary 是原始 TCP payload，无 session ID。每个本地 TCP 会话独立一个 WSS。
Client 不发 JSON 业务 Ping，因为当前 Worker 忽略 Client 的文本消息。

核对 Worker 的 SHA-256：`ff5c23e22a0989d885c5457f41a5f673ae6f45552b7c98ddfa9fd52eab6afcee`。Android 项目不修改此 Worker。
