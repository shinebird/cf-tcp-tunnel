# Theme NewApi 修复（SDK 37.0 工程）

## 原因与范围

之前 `app/src/main/res/values/styles.xml` 把 `android:windowLightNavigationBar`
放在没有版本限定的主题中。该 XML 属性从 API 27 引入，而工程的 `minSdk` 为 26，
所以 `:app:lintDebug` 报 `NewApi` 错误。这不是 SDK 37.0 路径、代理或 JDK 的故障。

本补丁只调整主题资源，并增加静态回归检查与说明。网络代码、Manifest、应用版本、
compileSdk 37.0 / Build Tools 37.0.0、targetSdk 36 / minSdk 26、
AGP 9.1.1 / Gradle 9.3.1，以及 PowerShell 的代理/JDK 参数均保持不变。

## 安装补丁

将补丁 ZIP 的内容解压到有 `app/`、`tools/` 和 `build.gradle` 的工程根目录，合并并覆盖。
修复运行所必需的文件只有两个：

- 覆盖 `app/src/main/res/values/styles.xml`
- 新增 `app/src/main/res/values-v27/styles.xml`

必须同时应用两个文件。不要把 `values-v27` 放在 `values` 里面。
不需要修改 SDK 安装目录，也不需要禁用 Lint 或设置 Lint baseline。

通用设置移入 `AppThemeBase`。API 26 的 `AppTheme` 仅继承这个基础主题；
API 27+ 的 `AppTheme` 同样继承基础主题，再启用 `windowLightNavigationBar`。
Manifest 仍使用 `@style/AppTheme`，因此原有字体、强调色、背景等配置继续保留。

## 重新构建

在工程根目录执行原命令即可：

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot" `
  -AndroidSdkPath "D:\Software\IDE\Library\Android\Sdk" `
  -CompileSdk '37.0' `
  -BuildToolsVersion '37.0.0'
```

不要添加 `-SkipLint`。Gradle 会检查资源变更，无需为了这次修复清空全部下载缓存。
成功后的安装包：`app/build/outputs/apk/debug/app-debug.apk`。

## 验证边界

可运行：

```powershell
py tests/check_theme_resources.py
py tests/check_build_config.py
py tests/run_core_tests.py
```

第一个是本地 XML 和主题继承/版本限定的静态回归检查，**不等同于 Android Lint 或 AAPT 验证**。
第三个仅验证 Java 网络核心，不能替代 Android 构建或设备测试。
本次环境没有 Android SDK、Gradle 或 Windows PowerShell，未运行 Android Lint、APK 构建或真机测试。
使用上述构建命令在你电脑上执行 `lintDebug`，才是实际 Android 构建验证。

原日志提到的 6 条警告没有具体内容，本补丁没有猜测或屏蔽它们。
`Deprecated Gradle features ... Gradle 10` 提示不等于当前使用 Gradle 10；
原日志明确的失败任务是 `:app:lintDebug` 的 `NewApi` 错误。

## 官方依据

- API 26 → 27 新增 XML 属性：
  https://developer.android.com/sdk/api_diff/27/changes/android.R.attr
- 资源版本限定与默认资源：
  https://developer.android.com/guide/topics/resources/providing-resources
- 主题继承与版本特定主题：
  https://developer.android.com/develop/ui/views/theming/themes
