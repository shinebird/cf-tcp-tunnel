> **1.1.2：现代化界面更新**。基于已验证可用的 1.1.1，采用“概览 / 配置 / 日志”三页布局，核心和后台逻辑不变。详见 [界面更新说明](docs/UI_REFRESH.md)。

> **1.1.1 / r5：后台转发加固 + Release 编译。** 安装后请点击“允许后台持续转发”，允许电池优化豁免，并在系统应用设置中允许后台活动。完整更新和真机验证步骤见 [docs/BACKGROUND_RELEASE.md](docs/BACKGROUND_RELEASE.md)。

> **当前工程：background-release-r5，无 vcpkg。请解压到新目录构建，不沿用旧 .cxx 或 .native-deps。**

# CF TCP Tunnel Android 1.1.2 Native — PC C++ core port

**完整源码工程，不包含 APK 或已编译的 Android `.so`。** 当前 background-release-r5 改用本地源码直接构建；已执行的检查及未验证项目见 [当前测试报告](docs/LOCAL_SOURCES_TEST_REPORT.md)。本版尚未在 Windows NDK 或真实手机完成构建和运行验证。

## 1. 这次与 Java 1.0.2 的区别

实际隧道从旧 Java `Tunnel/Wss/ByteQueue/Control` 切换为 PC 的 Boost.Asio/Beast/JSON + OpenSSL + C++20 协程。
Java 仅负责界面、前台服务、加密配置、日志、计数器、系统信任证书导出以及可选的本地 RDP 探测；不再收发隧道里的 RDP/HTTPS 载荷，不使用 Android `SSLSocket` 做 Worker 连接，没有自动回退到旧 Java 网络引擎。

基线为本对话中 `cf-tcp-tunnel-multi-agent-reconnect-fix.zip` 的 `cpp/src/main.cpp`，不是自行按协议再次写一份 C++。这次刻意不叠加 Hibernation 心跳优化或 Cobalt 迁移。基线使用 `asio::awaitable`，Agent 仍保留原来的 30 秒业务心跳。手机仅作 Client 时，远端 PC Agent 的心跳策略不受本 App 改变。

核心路径：
```
Windows App on Android
  -> phone 127.0.0.1:13389
  -> native libcf_tunnel.so (PC Proxy + Asio + Beast + OpenSSL)
  -> WSS /connect?agent=dorm-pc&port=3389
  -> existing Worker -> existing PC Agent -> Windows RDP
```

源代码可追溯：
- `native/upstream/pc-main.cpp`：PC 原始文件，字节未改。
- `native/src/pc_core.inc`：编入 `.so` 的移植核心。
- `native/pc-core-adaptation.patch`：完整差异。
- `tests/check_native_port.py`：剥除日志、计数和生命周期注册钩子后，断言核心与 PC 源码完全相同（不含原 `main()`）。

改动集中在：替代可执行程序 `main()` 的可启停 Engine、JNI 的安全句柄和生命周期、导出 CA 到 OpenSSL、日志与流量计数，以及结束 App 时清理 PC 原代码中活动的对象。**原网络传输循环、帧格式、读写顺序、队列、ready/registered 检查、端口映射和重连行为保留。**

## 2. 安装后参数

模式选 Client；Worker 填 `wss://tcp-tunnel.example.com`；Agent ID 填 `dorm-pc`；Token 填对应 Client Token；入口主机可以继续填 `node.example.com`；本地端口 `13389`，远端端口 `3389`。
Windows App 添加远程 PC：`127.0.0.1:13389`，网关无，使用目标 Windows 账户。无需修改 Worker 或 PC Agent。

默认校验服务器证书，原来的显式跳过验证选项保留。OpenSSL 使用从 Android 默认 TrustManager 导出的 CA；这不是对 Android 平台全部 TLS 策略（例如 CT、吊销、企业策略）的完全复制。导出/导入失败会报错，不会偷偷关闭验证。日志里应出现 `NATIVE_ENGINE C++ Native / PC reconnect-fix / Android NDK / Boost ... / OpenSSL ...`，而不是旧 `provider=AndroidOpenSSL` 日志。

多 Agent ID、独立/全局 Token、自定义连接 IP/CNAME、多 Client 会话、手机 Agent 模式均保留。手机 Agent 必须使用不与电脑冲突的 ID。本版保留 PC 重连逻辑，**没有**旧 Java 版的“被同名 Agent 替换后停止重连”额外行为；同名实例可能互相替换，不要重复 ID。

原 Java 的 16 会话/线程池限制不再是 Native 版的限制；Native 版本遵循 PC 核心及 Worker 的会话规则、4 MiB Agent 队列保护。无 UDP、无完整 TCP 半关闭、无端到端会话恢复。断线后原 RDP TCP 需要重建。

## 3. 本地源码构建 r2（不使用 vcpkg）

请先阅读 [本地构建说明](docs/LOCAL_SOURCES.md)。本版要求同时指定本地 Boost、OpenSSL Git 根目录，直接使用 NDK + MSYS2 Perl/make 编译 OpenSSL，NDK CMake 编译 Boost.JSON；没有 vcpkg 下载或安装路径，也不再下载 Boost/OpenSSL。

默认 NDK **30.0.16248370**，CMake **4.1.2**。JDK、SDK、代理参数保留。新的完整命令和 prerequisites 见上述文档。

本次仅修改依赖构建流程；未在你的 Windows/NDK 环境实际产出 APK。旧版主机测试记录不等于本版 Windows 构建验证，当前验证范围见 [r2 测试报告](docs/LOCAL_SOURCES_TEST_REPORT.md)。

## 4. GitHub Actions

独立 Android 仓库根直接放 `app/`、`native/`、`native-deps/`、`tools/`、`.github/` 等目录，不要只放 ZIP，不能覆盖 Worker 仓库。

附带 `Build Android Native APK` 工作流，在 Ubuntu 上准备 SDK/NDK，编译依赖，运行源码/主题检查，再 assembleRelease + lintRelease。成功后构件 `CF-TCP-Tunnel-Android-1.1.1-native` 包含 APK 和 SHA256。支持原 `DEBUG_KEYSTORE_BASE64` Secret。

**该工作流本次未在你的账号执行；没有现成 APK。** 原生 `.so` 要经过 ELF 架构和 16 KiB LOAD 对齐检查，APK 要经过 zipalign 16 KiB 检查。通过静态对齐检查不等于已完成所有 16 KiB 设备运行测试。

## 5. 历史移植验证记录： 当前验证边界

以下为原版 1.1.0 留存记录，并非 r2 重跑结果。原测试环境：Linux x86_64、GCC 14.2、Boost 1.83 头文件、OpenSSL 3.5.7、OpenJDK 21（`javac --release 17`）。

完成：C++ `.so` 编译链接、Java NativeTunnel 和 Harness 编译、JVM 经 JNI 调用真实 C++ 网络核心的 7 个集成场景、10 个源码迁移检查、8 个主题资源检查、Bash 语法检查。

7 个场景覆盖：1 MiB + 4 并发字节一致性、CA 导出及 Host/SNI 保持、拒绝不受信任/错误主机名证书、WebSocket 分片与 Ping 插入、Native Agent 3 次强断重连后新会话、同进程同端口 4 次停止/重启、RDP 形状的 19 字节协商后真实内层 TLS + 512 KiB HTTP。

**最后一项不是真实 Windows/CredSSP/RDP 测试。** 尚未完成：Android NDK 编译、Windows 原生依赖构建、Android APK/Lint、手机、Cloudflare、Windows App 登录桌面。PC 可用并不自动证明 Android 可用；它降低实现差异，而不是消除平台、编译器、库版本、权限/省电和手机 RDP 客户端差异。

旧 Java 源码/测试在 `reference/` 仅供审计，不进入 App 源集；旧测试结论不代表新核心。`tests/native-test-result.txt` 为本次实际主机测试摘要。

## 6. 本地重复运行主机测试（Linux，有 Boost 头文件/OpenSSL/JDK/Python）
```bash
cmake -S native -B .native-host-build -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build .native-host-build -j2
mkdir -p .test-classes
javac -encoding UTF-8 --release 17 -d .test-classes app/src/main/java/uk/ayachi/cftunnel/core/*.java tests/NativeHarness.java
python -m pip install -r tests/requirements.txt
python tests/check_native_port.py
python tests/check_theme_resources.py
python tests/native_integration.py --library-dir .native-host-build
```
不要把 Linux 主机测试生成的 libcf_tunnel.so 复制进 Android APK。Gradle 必须用 NDK 为 arm64-v8a/x86_64 重建。

## 7. Android 平台仍然存在的限制

前台服务、通知、显式停止和可选 WakeLock 保留；NDK 不会绕过 Doze、电池/后台限制或 AdGuard。仅监听手机 127.0.0.1；同机其他 App 可能访问本地端口。不得把这份尚待真机验证的移植版作为唯一远程访问手段。源码仅使用用户主动启动的可见服务，不含隐藏运行、远程启动或开机自启。
