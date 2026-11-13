# 1.0.2：Android RDP 连接诊断与传输层健壮性修订

## 范围与结论

这是针对“PC Client 正常，但 Android Windows App 一直等待连接”的诊断测试版。
仅凭该现象，尚不能确认根因；没有宣称已在用户手机上复现或修复该故障。
核对了现有 C++ Client / Worker 的 `/connect?agent=...&port=...`、Bearer Token、ready、二进制消息格式，协议不变。
无需更新 Worker 或 C++ Agent。没有修改 RDP 内容、绕过 NLA、关闭远程桌面认证或默认关闭 TLS 验证。

## 使用顺序

1. 在原工程根目录合并补丁，或者解压完整工程。保留本机原来的 debug.keystore，以相同签名覆盖安装；不要为了测试先卸载旧 App。
2. 使用原命令构建：

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath 'D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot' `
  -AndroidSdkPath 'D:\Software\IDE\Library\Android\Sdk' `
  -CompileSdk '37.0' `
  -BuildToolsVersion '37.0.0'
```

3. 安装 `app/build/outputs/apk/debug/app-debug.apk`，确认界面版本 1.0.2。仍执行 Lint，不需要 `-SkipLint`。
4. 手机隧道选 Client，Worker URL / Agent ID / Client Token 与 PC 的 Client 一致。本地端口 13389，远端端口 3389。
5. 启动后先点击“RDP 连通性自检（不需要密码）”。它使用**当前运行配置**，不是尚未重新启动应用的表单值。
6. 自检完成后，在 Windows App 中添加/编辑一个远程 PC，电脑名称为 `127.0.0.1:13389`，Gateway/网关为“无”，账户是目标 Windows 的登录账户（非隧道 Token）。
7. 再现失败后返回隧道 App，点击“复制日志（不含 Token）”。日志包含地址、Agent ID 和字节数；分享前可遮盖地址与 ID，但不要遮盖状态、错误或计数。

自检建立额外的短 TCP 会话，然后主动断开；它后面的 `LOCAL_EOF` / `SESSION_END` 是正常结果。
`RDP_CHECK PASS` 只验证初始 TPKT/X.224/RDP_NEG_RSP 往返，不验证 TLS/CredSSP/用户名密码或完整桌面；后续仍需 Windows App 实际连接。
收到 `RDP_NEG_FAILURE` 表示远端有回复但拒绝此次协商；不是“密码错误”。

## 新诊断日志

每个本地 TCP 会话使用独立编号，例如 `[C1]`。自检和 Windows App 的连接通常是不同编号，必须分别看。

| 最后阶段 / 现象 | 解释与下一步 |
|---|---|
| 只有“已监听”，Windows App 尝试时没有 `LOCAL_ACCEPT` | Windows App 尚未连入本 App；核对 `127.0.0.1:13389`、Gateway、Client 模式及服务是否仍在运行 |
| `DNS_BEGIN` 没有 `DNS_OK` | 手机实际 DNS 解析阶段；对照是否手动指定 IP/CNAME |
| `TCP_CONNECT` 没有 `TCP_OK` | 手机到入口 IP 的 TCP 连接问题；PC 入口可用不保证手机路径可用 |
| `TLS_BEGIN` 后异常 | 看证书、主机名及 TLS 错误；不要盲目关验证 |
| `WS_UPGRADE` 后 HTTP 401/403/503 | 对应鉴权、ID/端口限制、Agent 离线或配置异常 |
| `WS_101` 后没有 `READY` | WebSocket 握手已成功，但目标 TCP 建立/Worker ready 尚未完成 |
| `READY`，`TCP↑=0` | 本地应用建立了 TCP，但尚未写数据 |
| `TCP↑>0`，`WSS↑=0` | 数据尚未写入 WSS 输出流；查看队列和写错误 |
| `WSS↑>0`，`WSS↓=0` | 向外写过数据，尚未收到 TCP 响应；需要结合 Agent 端日志 |
| `WSS↓>0`，`TCP↓=0` | 收到远端数据，但未写回本地应用 |
| 四个计数都有数据，仍等待 | 仅证明部分双向传输，不能排除后续传输/登录问题；检查 Windows App 及服务端 RDP 日志 |

`TCP↑` = 从本地应用读到的字节，`WSS↑` = 成功交给 TLS 输出流并 flush 的二进制有效载荷；后者不是远端确认收到。
`WSS↓` = 收到的二进制有效载荷，`TCP↓` = 成功写入本地 TCP 输出流的字节；不是 Windows App 已经处理/显示。
日志不记录实际 TCP 载荷、Token 或 Windows 密码。
默认每会话在 15 秒后记录一次汇总，之后每 60 秒记录；不在 Worker 上增加探测消息。

## 传输层修订

- 连通性诊断覆盖 DNS/TCP/TLS/HTTP Upgrade/ready/首个双向数据，各阶段不再只有“已监听”的状态。
- 原本 Pipe 的读写异常被吞掉，现在记录方向与异常，不记录载荷。
- TLS 数据阶段取消 1 秒轮询式 SO_TIMEOUT，使用持续阻塞读取和独立看门狗。保留 90 秒接收空闲、30 秒写超时及协议 Ping。
  这是一项健壮性修订，并未证明旧的 1 秒超时是用户这次失败的原因。
- 超时或停止时先关闭底层 TCP，再释放 TLS，避免阻塞 TLS 读写无法及时退出。
- socket 安装与取消状态同步，避免停止与新连接安装竞争。
- DNS 调用等待 15 秒，总连接过程增加 60 秒截止；系统原生 DNS 调用不一定可中断，但调用方停止等待，结果不会继续用于新连接。
- 按读取进度刷新活跃时间，不等到整条消息收完才更新。
- 仍默认验证外层 TLS，仍只监听 127.0.0.1，不新增 VPN、不接管全手机流量，不扩大到局域网监听。

## 手机侧对照

有 AdGuard 或其他 VPN/HTTPS 检查时，先针对本 App 做路由排除对照，不直接修改仍正常的 PC Agent 或 Worker。
日志的 `vpn=` 只是系统默认网络标记，不足以判断每个进程是否真的被某个 VPN 代理。
确认进入 Windows App 后前台隧道通知仍存在；Android Doze/厂商后台限制仍可能影响网络，不能仅凭通知断言应用始终有网络。

## 保留内容

SDK 37.0 目录检测、Build Tools 37.0.0、AGP 9.1.1、Gradle 9.3.1、JDK/代理参数、API 27 主题限定资源均保留。
minSdk=26、targetSdk=36、applicationId、Manifest 和 Keystore 存储方式不变。versionCode=3，versionName=1.0.2。

## 验证边界

已执行纯 Java 核心编译、104 项核心断言、20 个本地实际 TCP/TLS/WebSocket 集成场景、55 项构建配置静态检查及 8 项主题 XML 测试。
集成包括分段 RDP_NEG_RSP、自检结束清理、计数、嵌套 TLS 数据传输、1.3 秒分段帧间隔、忽略 Pong 时强制取消读取。
测试服务模拟 Worker 协议，不是 Cloudflare 环境。不含实际 Windows RDP 登录、Android TLS provider、APK 构建、Android Lint 或真机测试。
本包是源码，不含编译好的 APK。完整结果见 `TEST_REPORT.md`。
