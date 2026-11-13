# background-release-r5：本地 Boost / OpenSSL，完全去除 vcpkg

这是完整 Android C++ 工程。此修订仅更改依赖构建，不改 Worker、PC Agent 或 Android 隧道协议。

## Windows 前置条件

- JDK 17、SDK Platform 37.0、Build Tools 37.0.0。
- NDK **30.0.16248370**、CMake **4.1.2**（均可用参数覆盖；NDK 至少 r28）。
- Windows Python 3.10+、Git for Windows 可从 PATH 调用；可用 `-PythonPath` 指定 Python.exe。已有 Conda 的 Python 可以使用，无额外 Python pip 包。
- 本地 Boost Git 根目录，版本 >=1.83。支持带 boost/ 的头文件布局，以及 libs/*/include 的模块化布局；所需子模块必须已初始化。
- 本地 OpenSSL 3.x/4.x Git 根目录；包含 Configure、Configurations、VERSION.dat。支持识别 4.2.0 开发树，但不代表任意开发分支提交均已验证可编译。
- **已有 MSYS2**，安装其中的 MSYS `perl` 和 `make` 包。使用 `<MsysRoot>\usr\bin\perl.exe`、`make.exe` 和 `bash.exe`，不使用 Strawberry Perl、MinGW Perl 或 mingw32-make。缺少时在 MSYS2 终端手动执行 `pacman -S --needed perl make`。本工程不运行 pacman、不自动安装工具。

MSYS2 仅提供 OpenSSL 所需的 Unix 构建工具。实际 C/C++ 编译器仍是你指定的 **Android NDK Clang**，不会链接 Windows 的 OpenSSL/Boost 库，也不需要 vcpkg/Visual Studio 来编译依赖。

建议解压到新目录，不要混入旧 `.native-deps`、`.cxx`、app/build。原 Git 源码目录无需重新 clone。

## 按当前机器路径构建

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath 'D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot' `
  -AndroidSdkPath 'D:\Software\IDE\Library\Android\Sdk' `
  -BoostSource 'D:\Library\boost' `
  -OpenSSLSource 'D:\Library\openssl' `
  -MsysRoot 'D:\Software\Tools\msys64' `
  -NativeAbis 'arm64-v8a' `
  -NdkVersion '30.0.16248370' `
  -CmakeVersion '4.1.2'
```

若 MSYS2 实际安装位置不同，只调整 `-MsysRoot`。省略时依次检测 `D:\Software\Tools\msys64`、`C:\msys64`、`D:\msys64`。

先验证参数可在上述命令末尾加 `-CheckOnly`：会执行 Python/Git/Perl/make 检查，但不会下载、编译或创建原生依赖缓存。它不是完整交叉编译或联网测试。

- `-NativeJobs 4`：OpenSSL make 并行数；内存不足可降低。
- `-NativeAbis 'arm64-v8a,x86_64'`：两个 ABI 分别编译；不支持 32 位 ABI。
- `-NativeWorkRoot 'D:\cf-tunnel-native-work'`：指定 OpenSSL 临时源码及构建缓存位置。默认工程下 `.native-deps/direct`。
- OpenSSL 上游 Makefile 对特殊路径支持有限，**NDK 和 NativeWorkRoot 路径不得含空白、引号及所检查的 shell 特殊字符**。工程路径含空格时指定无空格的 `NativeWorkRoot`；本地源码/JDK 路径可以含空格。
- `-NdkPath`：直接指定 NDK 根目录，优先于 `-NdkVersion`。
- `-VcpkgPath` 已删除，不再有隐式回退。

APK：`app/build/outputs/apk/release/app-release.apk`。CI 还会检查 APK 原生库和页面对齐。保留现有 debug 签名的方式见原项目说明，重装前注意设备上签名是否相同。

## 构建行为

1. 检查本地源码、工具和 ABI；缺失时立即报错，不回退 clone。
2. 读取 OpenSSL Git 已跟踪文件的当前内容，包含已修改的 tracked 文件；排除 `.git`、未跟踪的编译产物、可选测试子模块。你的原始源码、分支、工作区不被修改。
3. 在独立副本运行 OpenSSL 官方 `Configure android-arm64` / `android-x86_64`，API 26、PIC、静态库；使用 `no-shared no-tests no-module no-dso no-engine`。不套用 vcpkg 补丁。
4. Windows NDK 的 `.cmd` compiler wrapper 不适用于 make 的 POSIX shell，脚本只在副本中生成 POSIX wrapper，调用原 NDK `clang.exe --target=...26`；不修改 SDK。
5. `make build_libs` 和 `make install_dev` 生成头文件、libssl.a、libcrypto.a。按源码内容、NDK 路径/版本、ABI、驱动及工具版本生成缓存键，只有成功构建才能复用。
6. 安装到 `.native-deps/installed-direct/<ABI前缀>`，与旧 vcpkg 缓存完全隔离；失败不会发布新依赖。
7. Gradle/CMake 直接使用这些静态库和本地 Boost 头文件，使用同一 NDK 编译 Boost.JSON 与 App 的 JNI 库。生成许可证和 Android Studio 本地依赖选择配置。

自定义新增 OpenSSL 源文件若尚未被 Git 跟踪，会被排除；先由你决定是否 `git add`。脚本不会替你暂存修改。若构建进程被强制终止，可能残留 `.native-deps/direct-build.lock` 或 NativeWorkRoot 下 `.build.lock` 目录；确认所有相关构建均已退出后才手动删除。

**依赖源码阶段没有网络下载。** Gradle 分发包、AGP/Maven 依赖首次构建仍可能联网，继续受 `-ProxyUrl` 控制。GitHub Actions 在全新机器上会显式 clone 其指定的 CI Boost/OpenSSL 版本，这是 CI 的独立步骤；本地脚本不执行这些操作。

## Linux / CI

设置 `ANDROID_HOME`、`CF_BOOST_SOURCE`、`CF_OPENSSL_SOURCE` 后运行 `bash tools/build.sh`。可设置 `ANDROID_NDK_HOME`、`CF_CMAKE_VERSION`、`CF_NATIVE_WORK_ROOT`、`CF_NATIVE_JOBS`、`CF_ABIS` 和 `CF_PYTHON`。须已有 Perl/GNU make/Python/Git，NDK 使用 Linux 对应宿主包。CI 采用明确的 Boost 1.88.0 / OpenSSL 3.6.0 tag，用户本地版本由自己的源码决定。

## 故障定位

- 日志应出现 `[Local]` 和 `[Native] Building local OpenSSL` / `Reusing completed`；不应出现 cloning vcpkg 或 downloading vcpkg.exe。
- OpenSSL 完整输出位于 `NativeWorkRoot/<ABI>/<hash>/build.log`，配置/编译失败会保留副本供检查。
- 你以前遇到过 MSYS2 进程被注入组件影响而卡住。如果连 `-CheckOnly` 的 Perl/make 都挂住，需要先解决该 MSYS2 环境；本脚本不改注入设置、不申请管理员权限。
- 本次没有 Windows NDK30 实机编译、APK/Lint 或手机端验证。实际证据见 LOCAL_SOURCES_TEST_REPORT.md。

## r3：MSYS2 Perl 识别修复

旧脚本只接受 Perl `$^O=msys`，会错误拒绝返回 `cygwin` 的新版 MSYS2 Perl。现在兼容两个标识，固定调用 `/usr/bin/perl`、`/usr/bin/make`，并打印实际 Perl 路径和 OS 标识。MSWin32 Perl 仍被拒绝。

官方说明：https://www.msys2.org/news/ （MSYS/Cygwin 兼容性变更）。此次只需覆盖 `tools/openssl-build.sh` 即可；也可以重新解压完整工程。原构建参数不变，无需降级或重装 Perl。

## r4：Boost.JSON 的 Charconv 链接依赖

新版 Boost.JSON 序列化 double 时调用 Boost.Charconv。现在 CMake 检测本地 JSON 对 Charconv 的 include 依赖，调用同一 Boost 树中 libs/charconv/CMakeLists.txt，以相同 NDK/ABI/STL 编译 PIC 静态库，并由 cf_json_local PUBLIC 链接 Boost::charconv。使用本地头文件，不查找桌面库，关闭 Charconv 测试和可选 quadmath。没有 Charconv 依赖的旧 JSON 保持原构建方式。

升级只需覆盖 `native/cmake/LocalBoost.cmake`，然后重跑原 build-apk.ps1 命令。无需删除 .native-deps 或重编 OpenSSL。完整压缩包另附 `tests/boost-link`，用于共享库严格链接与 double JSON 往返测试。缺少 libs/charconv 子模块时，CMake 会在配置阶段明确报错；脚本不会自动下载。
