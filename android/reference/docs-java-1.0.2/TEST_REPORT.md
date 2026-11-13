# 1.0.2 实际验证报告

交付网络核心在 Linux OpenJDK 21（javac --release 17）、Python 3.13、websockets 16.0、OpenSSL 环境测试。

- `python tests/run_core_tests.py`：编译通过，104 项断言通过。
- `python tests/integration.py`：20 个实际本地 TCP/TLS/WebSocket 场景通过，结果如下。
- `python tests/check_build_config.py`：55 项静态检查通过。
- `python tests/check_theme_resources.py`：8 项主题资源测试通过。
- 先前 SDK37.0 / 代理 / JDK 构建脚本保持字节级一致；Manifest、主题资源、配置存储与基线一致。

## 未验证

没有 Android SDK、设备或模拟器：未执行 APK 构建、aapt、Android Lint、Android 系统 TLS provider、后台生命周期和通知真机测试。
没有访问用户 Worker/Token，也没有连接用户的 Windows RDP。没有确认本次真实故障的根因或证明已修复。
RDP 自检只发送初始标准协商；集成中的嵌套 TLS 是独立测试 TLS echo，不执行 CredSSP 或 Windows 登录。

## 集成结果

```text
PASS Client: trusted TLS, IP override retains Host/SNI, 1MiB, 4 concurrent, repeated sessions
PASS TLS: default rejects unknown CA
PASS TLS: trusted CA still rejects wrong hostname
PASS TLS: explicit insecure option
PASS Agent: 4 clients + 3 reconnects accepting new clients
PASS Agent: port allowlist and uint32 ID
PASS Keepalive: native
PASS Keepalive: normal
PASS Keepalive: wrong
PASS Keepalive: ignore
PASS Agent: registration ACK deadline and reconnect
PASS Agent: replacement stops reconnect conflict
PASS Frames: fragmentation, interleaved Ping, drain before Close
PASS Frames: reject masked server frame
PASS Client: ready ACK deadline
PASS Stop: cancellation during blocked TLS handshake
PASS RDP probe: WSS loopback, split confirmation, stages and delivered byte counters
PASS Client: nested TLS handshake and encrypted payloads over WSS
PASS Client: delayed partial WebSocket frame remains byte-exact
PASS Watchdog: no Pong; blocking TLS read is cancelled by raw socket close
PASS ALL 20 integration scenarios
```

临时测试证书、私钥、信任库和编译产物均未打包。
