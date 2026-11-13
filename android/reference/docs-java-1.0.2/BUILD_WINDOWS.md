# Windows 构建修复：SDK 37.0 / Build Tools 37.0.0 + 代理 + 指定 JDK

适用于 CF TCP Tunnel Android 1.0.1；这次更新构建脚本和 Gradle 配置，不修改 App 的 Java 源码、Manifest、资源、版本、协议、Worker 或 C++ 客户端。

## 本次修复：不是 SDK 没装，而是旧脚本假定了目录名称

报错的上一版只检测 `platforms/android-37/android.jar`，没有检测你实际安装的 `platforms/android-37.0/android.jar`。本版默认按 37.0 检测，优先使用 `android-37.0`，缺少它时再尝试旧 `android-37`；只接受有 `android.jar` 的候选目录。

**不重命名、不复制、不创建目录链接、不编辑 SDK 的 package.xml/source.properties。** 若只装了 `android-37.1`，不会悄悄把它当成 37.0。此时会报告尝试过的路径与实际平台目录。

`-CompileSdk` 改为字符串以保留 `.0`。Windows 脚本把选择到的完整版本传给 Gradle；`app/build.gradle` 使用正式的 `compileSdk { version = release(37) { minorApiLevel = 0 } }` DSL 指定 37.0，旧的 major-only 目录保留原整数写法。AGP 9.1.1 / Gradle 9.3.1 / JDK 17 不需要再次升级。

用你当前目录运行：

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot" `
  -AndroidSdkPath "D:\Software\IDE\Library\Android\Sdk" `
  -CompileSdk '37.0' `
  -BuildToolsVersion '37.0.0'
```

末尾加 `-CheckOnly` 时不下载、不构建，输出应包含 `[Platform] platforms;android-37.0` 和实际 `android.jar` 路径。`-CheckOnly` 不能证明 Gradle 或 APK 已构建成功。

## 安装补丁与版本选择

将 SDK37.0 路径修复补丁中的内容合并到工程根目录并覆盖同名文件。**不要只覆盖 `tools/build-apk.ps1`**；根目录和 app 目录下的 `build.gradle` 也必须更新。完整工程 ZIP 与补丁二选一即可。

| 设置 | 本版值 |
|---|---|
| compileSdk | 37.0（兼容 37 参数；也支持 36 / 36.0） |
| Build Tools | 37.0.0（可显式选择已安装的稳定 36.x.x / 37.x.x） |
| targetSdk | 36，保持原来的运行时目标 |
| minSdk | 26，保持原来的最低系统要求 |
| AGP | 9.1.1 |
| Gradle | 9.3.1 |
| 推荐 JDK | 17 |

API 37 的正式工具链最低要求是 AGP 9.1.1。该 AGP 对应 Gradle 9.3.1 / JDK 17，不应仍用 AGP 8.13.2 后通过 suppressUnsupportedCompileSdk 隐藏告警。当前只使用公共 Android DSL，没有 Kotlin 或第三方 Gradle 插件需要迁移。

compileSdk 用于选择编译时 SDK；targetSdk 控制部分运行时行为。**targetSdk=36 不要求你另装 Platform 36**，本版编译读取的是 `platforms/android-37.0/android.jar`（兼容旧 `android-37` 目录）。不自动提升 targetSdk 是为了避免仅因构建环境变化就引入 Android 17 的局域网运行时权限等适配需求。

旧 `.gradle-dist/gradle-8.13` 可留作缓存，但本脚本不会选它。第一次正式运行时下载并校验 Gradle 9.3.1，并覆盖生成对应 Wrapper。第一次运行应使用 `build-apk.ps1`，不要直接启动工程中的旧 `gradlew.bat`。IDE 使用本版工具链时，需选支持 AGP 9.1.1 的 Android Studio（官方表为 Panda 3 2025.3.3 Patch 1 或更新版本）；命令行构建不依赖安装该 IDE。

## 最常用命令

在工程根目录执行：

```powershell
.\tools\build-apk.ps1 -ProxyUrl 'http://127.0.0.1:7897' -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
```

路径中含空格时保留引号。`-JdkPath` 应是 JDK 根目录，其中应有 `bin\java.exe`、`bin\javac.exe`、`bin\jar.exe`；不要填 `bin` 或 `java.exe`。

被 PowerShell 执行策略拦截时，可仅对这次新进程放开：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
```

无需管理员、无需修改永久执行策略。Windows PowerShell 5.1 和 Windows 上的 PowerShell 7 均为脚本设计目标；源码用 UTF-8 BOM / CRLF 保存。

## 可用参数

| 参数 | 作用 |
|---|---|
| `-ProxyUrl` | 本次构建的 HTTP 代理，如 `http://127.0.0.1:7897`；可省略 |
| `-JdkPath` | 指定 JDK 根目录；优先于 JAVA_HOME / PATH |
| `-AndroidSdkPath` | 可选，自定义 SDK 根目录；别名 `-SdkPath` |
| `-CompileSdk` | 字符串版本，默认 `37.0`；接受 `36`、`36.0`、`37`、`37.0`，不会修改 targetSdk |
| `-BuildToolsVersion` | 默认 37.0.0；检查本机安装并传给 Gradle |
| `-CheckOnly` | 仅本地配置检查；不下载、不构建、不保证代理可连接 |
| `-SkipLint` | 只构建 APK，跳过 lintDebug；默认会运行 Lint |
| `-RefreshDependencies` | 给 APK 构建追加 Gradle 的 `--refresh-dependencies` |

旧入口完整兼容：

```powershell
.\tools\build.ps1 -ProxyUrl 'http://127.0.0.1:7897' -JdkPath 'D:\Java\jdk-17'
```

只指定 JDK、不覆盖代理：

```powershell
.\tools\build-apk.ps1 -JdkPath 'D:\Java\jdk-17'
```

同时指定 SDK：

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot" `
  -AndroidSdkPath 'D:\Android\Sdk'
```

## 代理覆盖范围

- PowerShell 对 Gradle 安装 ZIP、SHA-256 的下载，包括 HTTP 重定向，明确使用 `Invoke-WebRequest -Proxy`。
- Gradle Wrapper 生成和 APK/Lint 两次 Gradle 执行都带 HTTP/HTTPS Java 代理属性：`http.proxyHost/Port`、`https.proxyHost/Port`，不是只设环境变量。
- 子进程继承临时 HTTP_PROXY / HTTPS_PROXY / ALL_PROXY；Java 的参数仍单独设置。
- 代理例外限定为回环地址。已有 SOCKS 设置与代理用户名/密码在本次 Gradle 命令中清空，避免使用旧配置。
- 下载失败最多尝试三次；只重试相同代理，不降级到直接连接。缓存 ZIP 校验失败会删除并重新下载；校验仍失败则中止。
- 不关闭 HTTPS 证书检查，也不跳过 ZIP SHA-256 校验。

`http://` 描述的是代理入口协议，**不代表下载目标也必须是 HTTP**；访问 HTTPS 仓库由代理提供 CONNECT 隧道。7897 必须是 HTTP 或 mixed 端口。当前脚本不支持 SOCKS-only、HTTPS-to-proxy、用户名密码代理；会明确拒绝这些 URL。

不传 `-ProxyUrl` 意味着“不覆盖已有配置”，并不保证没有系统代理或用户 Gradle 代理。

这是 Android 工程的构建代理，**不会修改 App 运行时的网络配置**。脚本不负责 SDK Manager 的下载安装、不自动接受 SDK licenses、不运行 pip。首次使用前要在 SDK Manager 安装 SDK 37.0 / Build Tools 37.0.0；SDK Manager 自己的代理仍需在它的设置中配置。GitHub Actions 云端也不能使用你电脑的 `127.0.0.1:7897`。

## 指定 JDK 如何生效

选择顺序：显式 `-JdkPath` → JAVA_HOME → PATH 的 java.exe。选定后先验证 JDK 根目录和实际 Java 版本，再同时为本次构建设置：

```text
JAVA_HOME=<选定JDK>
PATH=<选定JDK>\bin;<原PATH>
-Dorg.gradle.java.home=<选定JDK>
```

每次 Gradle 执行都使用 `--no-daemon`，不复用长期运行的其他配置 daemon；Gradle 仍可能创建一次性构建进程，不等于完全不启动 daemon 进程。

当前 Gradle 9.3.1 / AGP 9.1.1 脚本保留保守的 JDK 17–23 检查范围，推荐并以 JDK 17 为参考配置。这是本脚本的范围限制，不是声明更高版本的 JDK 与 Gradle 一定不兼容；本次没有扩展 JDK 选择范围。若项目后来添加 `gradle/gradle-daemon-jvm.properties`，脚本会拒绝继续并提示处理该文件，因为它可以覆盖 JAVA_HOME / org.gradle.java.home。

既有 JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS / JAVA_OPTS / GRADLE_OPTS 不会被脚本删除；若其中注入了额外 Java agent、特殊 trustStore 或冲突配置，仍可能影响构建。出现非预期行为时先检查这些环境变量。

## SDK、缓存和恢复

SDK 选择顺序：`-AndroidSdkPath` → `local.properties` 的 sdk.dir → ANDROID_HOME → ANDROID_SDK_ROOT → `%LOCALAPPDATA%\Android\Sdk`。只检查已安装组件；缺少组件时立即报出路径。默认检查 `platforms/android-37.0/android.jar`（兼容旧 `android-37` 目录） 和 `build-tools/37.0.0/` 下的 `aapt2.exe`、`d8.bat`、`apksigner.bat`、`zipalign.exe`，不再检查 36 / 35.0.0。

版本参数的完整示例：

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot" `
  -AndroidSdkPath 'D:\Android\Sdk' `
  -CompileSdk '37.0' `
  -BuildToolsVersion '37.0.0'
```

脚本给两次 Gradle 调用都传递 `-PcfCompileSdk=37.0`、`-PcfBuildToolsVersion=37.0.0`，`app/build.gradle` 读取这些属性。直接在 Android Studio / GitHub 构建时，默认也是 37.0 / 37.0.0。用 `-CompileSdk 36 -BuildToolsVersion '36.0.0'` 回退时，需要已经安装对应组件；不接受未验证的 API 38 或 preview / rc 目录。

为了使显式 SDK 参数也能覆盖旧 local.properties，构建期间会临时追加选定的 sdk.dir。正常结束或捕获错误后，原文件逐字节恢复；原本没有文件时删除临时文件。不会修改用户级 Gradle 配置或 Windows 注册表。强杀 PowerShell / 断电可能跳过 finally；此时 local.properties 可能保留临时 SDK 路径，可自行检查，它不包含 Token 或代理密码。

下载缓存放在工程 `.gradle-dist/`，已有 .gitignore 会忽略它。已安装的 Gradle 和依赖缓存会复用，通常不必每次重新下载。正式 Wrapper 中会保留 distributionSha256Sum；不要将真实密钥加入源码。默认 APK 调试签名行为没有改变。

只允许同一工程的一份本脚本同时构建；文件锁在退出后释放。它不协调另外从 Android Studio / gradlew 启动的构建，避免同时改写 local.properties。构建路径不允许百分号、双引号或换行，以免 Windows cmd.exe 的二次解释破坏参数。

## 本地检查与自测

```powershell
.\tools\build-apk.ps1 -ProxyUrl 'http://127.0.0.1:7897' -JdkPath 'D:\Java\jdk-17' -CheckOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\test-build-script.ps1
```

第二条只测试 PowerShell 语法、SDK 版本/组件选择（模拟文件）、代理解析、属性转义、下载参数传递（mock）、Windows 批处理参数边界和环境恢复工具，不需要 Android SDK，不会真实联网下载依赖或生成 APK。

## 常见问题

| 现象 | 处理 |
|---|---|
| Proxy connection refused / 超时 | 先启动代理软件，检查 7897 是 HTTP/mixed 端口；CheckOnly 不做联网测试 |
| 下载 Gradle 成功，AGP 仓库仍报错 | 看第一次 Gradle 错误；确认代理允许 Google Maven、Maven Central、Gradle Plugin Portal 的 HTTPS CONNECT |
| PKIX path building failed / TLS 错误 | 检查 JDK 信任库及代理/AdGuard 的 HTTPS 解密；该参数不会禁用证书验证 |
| SDK component missing | SDK Manager 安装所选 Platform 37.0、Build Tools 37.0.0；或传正确的 -AndroidSdkPath |
| Java 版本不是预期 | 看脚本 [JDK]/[Java] 输出，传 JDK 根目录，检查额外 JAVA_* / GRADLE_OPTS 注入 |
| 直接运行 gradlew 又不用代理 | 本脚本参数不会永久写入项目；继续通过 build-apk.ps1 调用，或自行给 IDE/Wrapper 配置代理 |
| Gradle/Lint failed | 构建会返回错误而不是声称成功；检查上面的第一个真实错误。必要时用 -SkipLint 分离打包与检查 |

## 验证边界

本次环境没有 Windows PowerShell / PowerShell 7、Android SDK；网络下载也无法解析工具发行站。因此未执行 PowerShell 运行时、真实代理下载、AGP 依赖解析、Android APK 构建或 Lint。已执行跨文件静态配置检查、Bash 语法检查和 Java 核心回归测试，并核对未改变任何 App 源文件。Windows 自测覆盖仅 android-37.0、仅 android-37、两者同时存在、缺失 android.jar、其他 minor 不能代替 37.0、缺失 Build Tools 等用例，但本次未运行它。详见 `BUILD_TOOL_TEST_REPORT.md`。

## 参考

- Gradle 代理：https://docs.gradle.org/current/userguide/networking.html
- Gradle JDK / 属性优先级：https://docs.gradle.org/current/userguide/build_environment.html
- Daemon JVM criteria：https://docs.gradle.org/current/userguide/gradle_daemon.html
- Java/Gradle 兼容表：https://docs.gradle.org/current/userguide/compatibility.html
- AGP 9.1.1：https://developer.android.com/build/releases/agp-9-1-0-release-notes
- API 37 最低 AGP：https://developer.android.com/build/releases/about-agp
- Android 17 局域网权限：https://developer.android.com/privacy-and-security/local-network-permission
- PowerShell 5.1 Invoke-WebRequest：https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/invoke-webrequest?view=powershell-5.1

- Full compile SDK / minor API DSL：https://developer.android.com/build
- AGP CompileSdkReleaseSpec：https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/CompileSdkReleaseSpec
