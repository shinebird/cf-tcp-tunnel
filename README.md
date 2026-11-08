# Cloudflare TCP Tunnel — 休眠友好心跳优化版

基于 `cf-tcp-tunnel-multi-agent-reconnect-fix.zip` 修改。

**本轮只修改 C++ 保活策略。`src/index.js`、`wrangler.jsonc` 和 CMakeLists 与上一版相同；已部署重连修复版 Worker 的用户不需要重新部署 Worker。**

## 修改摘要

- 高频保活由 JSON 数据消息改为 Boost.Beast 原生 WebSocket 控制 Ping/Pong。
- 默认 90 秒接收空闲超时；约 45 秒未收到任何数据或控制帧时，Beast 自动发送协议 Ping。
- Cloudflare 运行时回应协议 Pong，不调用 DO 的 JavaScript 消息处理器，不打断休眠。
- 保留注册确认和低频应用层探测：默认每 600 秒发送一次旧协议的 JSON ping，等待匹配 nonce 的 JSON pong，响应超时 90 秒。
- 原生 Pong 只能证明 WebSocket 链路仍可响应，不证明 DO 中的 Agent 注册状态正确，因此默认没有完全取消业务探测。
- 业务探测每次只允许一个请求等待响应，避免用更新的 nonce 误判迟到的正常响应。
- `--app-probe-interval 0` 可关闭定期业务探测；注册确认、协议级保活和自动重连仍保留。
- Agent 和 Client 都启用原生保活。多 Agent、多个 Client、端口白名单、指定 IP/CNAME、可选 TLS 验证保持不变。

注意：默认业务状态故障发现速度从高频检查变慢到最坏约 600+90 秒；网络完全失去响应则由原生约 90 秒空闲超时检测。超时不包含 DNS、TCP 重连与重试退避花费的额外时间，系统休眠/本地事件循环阻塞也可能使检测延后。原生保活依赖异步 WebSocket 读取，不能把读循环停掉后继续期待及时超时。

## 编译

C++20、Boost 1.83+（Asio、Beast、JSON）、OpenSSL、CMake 3.21+。
Windows / Visual Studio 2022 / vcpkg：

```powershell
vcpkg install boost-json:x64-windows boost-beast:x64-windows boost-asio:x64-windows openssl:x64-windows
cmake -S cpp -B build -A x64 -DCMAKE_TOOLCHAIN_FILE="C:/vcpkg/scripts/buildsystems/vcpkg.cmake"
cmake --build build --config Release
```

使用自己的 Boost 安装时，沿用上一版的 CMake / 工具链配置即可。

## Agent

```powershell
.\cf-tunnel.exe agent `
  --url wss://tcp-tunnel.ayachi-nene.uk `
  --agent-id home-pc `
  --token YOUR_AGENT_TOKEN `
  --ports 3389 `
  --target-host 127.0.0.1 `
  --ws-idle-timeout 90 `
  --app-probe-interval 600 `
  --app-probe-timeout 90
```

后三个参数均有默认值，可以省略。无 Client 时也希望完全避免周期性 JSON 心跳唤醒，可使用 `--app-probe-interval 0`。这不等于禁用隧道所有保活，但会失去定期业务状态检查；更推荐保留默认 600。

## Client

```powershell
.\cf-tunnel.exe client `
  --url wss://tcp-tunnel.ayachi-nene.uk `
  --agent-id home-pc `
  --token YOUR_CLIENT_TOKEN `
  --listen-port 13389 `
  --remote-port 3389
mstsc /v:127.0.0.1:13389
```

`--ws-idle-timeout` 对 Agent 和 Client 均有效；两个 `--app-probe-*` 参数仅对 Agent 有意义。

## 原有功能

- `--connect-host <IPv4/IPv6/域名>`：仅覆盖 TCP 连接地址，TLS SNI、证书主机名验证、HTTP Host 仍使用 `--url` 的域名。不写 scheme、端口或 IPv6 方括号。
- `--insecure-skip-verify`：关闭证书身份验证，默认不启用。仅适合排障，不会关闭加密，但失去服务器身份认证。
- 默认 Agent ID 为 `main`。每个 ID 对应一个 DO，可以同时服务多个 Client / TCP 会话。同 ID 的新 Agent 替换旧 Agent，并终止旧 TCP 会话。
- Worker 的 `AGENT_TOKEN` / `CLIENT_TOKEN` 为共享鉴权；可选 `AGENT_TOKENS` / `CLIENT_TOKENS` 为 JSON ID→Token 映射。配置映射时完全覆盖相应共享 Token，未列出的 ID 被拒绝。
- Worker 的 `ALLOWED_PORTS` 是全局白名单；Agent 的 `--ports` 进一步限制目标端口。
- `--target-host` 决定 Agent 所访问的内网主机；`--listen-host` 默认 `127.0.0.1`，不要不必要地对公网开放 Client 本地端口。
- 使用 AdGuard 时保留 `cf-tunnel.exe` 的路由绕过规则。

密钥映射示例（不要把实际密钥提交至 GitHub）：

```json
{"home-pc":"REPLACE_WITH_STRONG_HOME_TOKEN","office-pc":"REPLACE_WITH_STRONG_OFFICE_TOKEN"}
```

## Worker 更新

已部署上一版重连修复版时，只需重新编译并重启 Agent 和 Client。压缩包附带未修改的 Worker 源码，方便保留完整工程。

不要用压缩包配置覆盖你自己的 Worker 名称、自定义域、CPU 限制或其他部署设置。仍使用更早的 Worker 时，需要先更新到支持 `registered` 确认和 JSON ping/pong 的重连修复协议。

## 验证结果与边界

本轮实测：

- Linux / GCC 14.2 / Boost 1.83 / OpenSSL 的 C++20 **编译和链接**通过，无编译警告。测试环境没有 Boost.JSON 的预编译库，链接测试使用本机 Boost 安装的 `boost/json/src.hpp` 编译实现；不改变用户工程的依赖方式。
- `node --input-type=module --check < src/index.js` 与 `node test-worker.cjs` 通过。
- 本地 WSS 测试通过：关闭业务探测后只有原生 Ping；业务探测正常回应；业务探测无回应和错误 nonce 导致重连；协议 Pong 消失导致空闲超时重连。
- 本地 C++ Agent + C++ Client + 模拟中继 + TCP Echo 测试通过：随机二进制数据、多 Client 并发、空闲后继续传输、强制断线重连后接受新的 TCP 会话。

运行本地集成测试：

```powershell
py -m pip install "websockets>=13,<17"
py tests/test_keepalive.py --binary build/Release/cf-tunnel.exe
```

测试还需要 PATH 中有 `openssl`；只使用本机测试证书和本机地址，测试进程会使用跳过验证模式。这不是生产部署建议。

**未验证：真实 Cloudflare 部署、GB-s 计费变化、Cloudflare 实际休眠行为、Windows/MSVC 运行与真实 RDP。无任何保证不超额的预算熔断。**

仍有上一版限制：无 UDP、无完整 TCP 半关闭、无端到端流量窗口；内存队列上限不是完整背压。Agent 断线后不能恢复已经断开的 TCP 会话。

计费分析和官方依据见 `HIBERNATION_AUDIT.md`；相对上一版 C++ 的补丁见 `cpp-keepalive.patch`。
