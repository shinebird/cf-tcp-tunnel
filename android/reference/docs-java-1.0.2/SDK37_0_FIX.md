# SDK 37.0 路径修复补丁

将本补丁的内容合并到原 Android 工程根目录，覆盖同名文件。
不要解压到 Android SDK 目录。不要重命名 platforms/android-37.0。
必须同时更新 app/build.gradle 和 tools 下的脚本，不能只替换检查路径。

```powershell
.\tools\build-apk.ps1 `
  -ProxyUrl 'http://127.0.0.1:7897' `
  -JdkPath "D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot" `
  -AndroidSdkPath "D:\Software\IDE\Library\Android\Sdk" `
  -CompileSdk '37.0' `
  -BuildToolsVersion '37.0.0'
```

可以先在命令末尾追加 -CheckOnly 检查路径；删除后正式构建。
输出应包含 platforms;android-37.0 与实际 android.jar 路径。
代理、JDK 功能保留；targetSdk 36、minSdk 26、应用源码和版本不变。
验证范围见 docs/BUILD_TOOL_TEST_REPORT.md；不包含预编译 APK。
