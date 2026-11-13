# 1.1.2 UI 更新验证（2026-11-13）

- Java 编译检查：0 错误；弃用 API 和既有 unused 提示保留。
- Native 来源/配置检查：10 项通过；主题资源检查：8 项通过。
- 与本轮开始前的 1.1.1 比较：27 个 native、core、Service、存储、Manifest、构建脚本文件内容未变。
- 原 7 个连接字段键全部保留，Token/mode/insecure 的保存逻辑保留。
- 没有进行真机或模拟器视觉渲染、APK/Lint 或实际后台网络测试。不能把 Java 编译检查当作视觉验收。

详见 UI_REFRESH.md。下方保留此前版本记录。

# 1.1.1 / background-release-r5 验证（2026-11-13）

本次是后台运行加固和 Release 构建更新；未取得真机后台日志，不能宣称已确认或根治设备上的冻结原因。

已完成：
- 所有生产 Java 源码编译检查通过（ECJ3.42，Java17 source/target；Android35 官方 SDK API stub；java/javax/XML 标准库使用主机 JVM，临时 R 常量占位）。零错误，保留既有弃用和未使用 import 警告。此检查不是 AGP/D8/Lint/APK 构建。
- 原生来源/服务连接检查 10 项通过；主题/XML 资源检查 8 项通过。
- Bash 构建脚本语法检查通过。Windows 构建脚本仅替换 Gradle task 和 APK 路径，本轮没有 PowerShell 执行环境测试。
- 检查构建入口一致切换到 assembleRelease/lintRelease 和 app-release.apk；CMake 对 Android 强制 Release，并新增原生 NDEBUG 状态日志。

未完成：完整 Windows NDK30/CMake4.1.2 构建、AGP Lint、APK 安装、手机后台/锁屏测试、厂商省电策略和实际 RDP 回归。没有将源代码检查当作运行效果证明。真机测试步骤及诊断说明见 BACKGROUND_RELEASE.md。

历史 r4 的本地 JSON/Charconv 真实主机链接测试仍适用于未修改的 LocalBoost.cmake；没有在本轮重新下载/重编这些依赖。

以下保留历史记录。

# local-direct-r4 增量验证（2026-11-11）

修复新版 Boost.JSON 调用 Boost.Charconv 后静态链接缺失的问题。生产代码修改只涉及 native/cmake/LocalBoost.cmake，OpenSSL 构建/缓存键未改。

实际验证（Linux x86_64、GCC14.2、CMake3.31.6）：

- 使用当前 develop 的 JSON（e93b1060d4bc46b09910b70fcc60594734eed95e）、Charconv（caf0c18c4de3741174eca5bacf49aa1b51a96828）和 Config/Core/Assert/System 头文件，Boost 版本头为 1.93；其余辅助头文件取自既有 1.88 头文件树。此组合用于验证链接修复，不代表完整 1.93 发行版或用户确切提交。
- 真正编译 Charconv 两个翻译单元、Boost.JSON，使用 --no-undefined 链接共享库成功。最终链接顺序为 libcf_json_local.a、libboost_charconv.a。
- JSON double 序列化/解析往返测试通过，含 0、负零、0.1、负小数、1e-200、1e200（数值等值检查）。
- 原 Boost1.88 无 Charconv 依赖的构建、严格共享库链接和相同往返测试通过。
- 模拟缺失 libs/charconv 子模块，配置阶段按预期报告 requires Boost.Charconv sources。

没有执行 Windows NDK30/Android 的完整 APK 构建或真机测试。

可用自己的本地 Boost 重跑：

```bash
cmake -S tests/boost-link -B .boost-link-test -DCF_BOOST_SOURCE=/path/to/boost
cmake --build .boost-link-test
ctest --test-dir .boost-link-test --output-on-failure
```

以下保留历史验证记录。

# local-direct-r3 增量验证（2026-11-11）

修复 MSYS2 新版 Perl 返回 cygwin 时的误拒绝；绑定 /usr/bin/perl 和 /usr/bin/make。

实际执行脚本预检的 fixture 回归测试 5 项通过：旧 msys、新 cygwin、MINGW shell、CYGWIN host 名称、MSWin32 拒绝并打印诊断。Bash 语法检查通过。构建编排的 3 项测试重跑通过。测试替换平台/工具输入，未在真实 Windows/MSYS2/NDK 环境编译 APK。

复跑：`python tests/test_msys_perl.py`。以下保留 r2 验证记录。

# local-direct-r2 验证记录（2026-11-11）

本次修订删除本地构建中的 vcpkg/bootstrap/overlay/manifest/triplet 文件；仅保留直接 NDK + OpenSSL Configure/make，以及本地 Boost 的 CMake 集成。

## 已执行

- 本地源码检查测试 7 项通过：模块化 Boost、OpenSSL 4.2.0 版本识别、确定性快照、dirty tracked 文件、排除未跟踪产物、可选 Git 子模块、缺失文件、Java properties 路径转义。
- 新的构建编排测试 3 项通过（mock builder）：CheckOnly 不创建缓存；ABI/NDK/源码变化触发重建，成功缓存命中、缺失安装文件修复；失败不替换原成功安装且释放锁；不支持 ABI 拒绝执行。
- 原生核心来源检查 10 项通过，Android 主题资源检查 8 项通过。
- PowerShell 7.4.6 parser 检查 build.ps1、build-apk.ps1、prepare-native.ps1 通过；Bash 语法检查通过；Python 语法检查通过。
- 对真实 OpenSSL 3.6.0、OpenSSL master 4.2.0-dev（commit cd99d918bf26de00b2905c654968215f845fdd55）的本地源码快照，各运行 android-arm64 / android-x86_64 Configure，四组成功生成 Makefile；确认 API26 和目标 Clang wrapper 名称。配置使用模拟 NDK clang，仅证明 Configure 参数/源码布局能接受，不是编译成功证明。4.2 的 Android 配置文件与此次 3.6 文件一致。
- 此前已验证 LocalBoost.cmake 以本地 Boost 1.88 头文件编译 Boost.JSON 和 tunnel_engine.cpp；此修订未更改这些目标代码。

## 尚未验证

没有在 Windows/MSYS2、NDK30/CMake4.1.2 上执行真实交叉编译，没有生成 APK，没有运行 Android Lint、真机 RDP 或 Cloudflare 端到端测试。未验证用户本地 Boost 1.93/OpenSSL 4.2 的确切提交组合。GitHub Actions 工作流只完成代码更新，未触发运行。

README 中旧的 JVM/JNI 集成测试记录是原始移植版留存证据，不应理解为 r2 在当前 Android 工具链上的结果。

## 在工程根目录复跑离线检查

```text
python tests/test_local_sources.py
python tests/test_native_build.py
python tests/check_native_port.py
python tests/check_theme_resources.py
```

test_native_build.py 的 fake-bash 执行器面向 POSIX 测试环境；Windows 使用 build-apk.ps1 -CheckOnly 检查真实工具，再正常构建。源码文件清单在 SOURCE_SHA256SUMS.txt。
