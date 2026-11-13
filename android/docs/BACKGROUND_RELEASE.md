# Android 1.1.1 / background-release-r5

## 这次修改

- 保留前台服务运行 C++ 网络核心；Activity 暂停只停止界面刷新。明确 stopWithTask=false，不随任务移除主动停止。
- 移除旧的默认关闭、最长 12 小时的可选唤醒设置；用户启动隧道后自动持有 PARTIAL_WAKE_LOCK，停止、启动失败或原生核心退出导致服务销毁时释放。屏幕可以关闭，不保持屏幕亮起；持续运行耗电会增加。
- 新增本应用专用的电池优化授权入口，以及应用设置入口；显示电池豁免/后台受限状态。授权必须由用户在系统界面完成，不会静默修改系统设置。
- 增加前后台切换、每 30 秒服务存活/流量、超过 6 秒的服务 tick 间隔、默认网络变动/被阻止状态日志，并以 CFTunnel tag 写入 logcat。不切换或绕开已有 VPN 路由。
- 默认脚本及 CI 改为 assembleRelease / lintRelease。CMake 明确使用 Release，JNI、Boost.JSON、Boost.Charconv 均继承 Release 配置。启动时原生日志显示 NATIVE_BUILD Release (NDEBUG=1)。
- 默认 APK 非 debuggable；仍用该电脑已有 debug signing key 给这个私人侧载包签名，以便覆盖原安装。使用 debug key 不代表编译是 Debug。没有为发布商店配置生产签名。
- versionCode=5，versionName=1.1.1-native。继续保留本地 Boost/OpenSSL、无 vcpkg、MSYS2 cygwin Perl 与 Charconv 修复。

## 安装和设置

本次修改涉及 Java、Manifest、Gradle、CMake 和脚本，**请更新完整工程文件**。可以覆盖已有源码工程并保留 .native-deps 缓存、local.properties、签名文件；不要只替换一个脚本。构建参数与 r4 相同，输出变为：

`app/build/outputs/apk/release/app-release.apk`

在同一台电脑、同一系统账户、同一签名文件下构建，通常可覆盖安装；签名不一致时无法覆盖。先停止旧隧道再安装。无需为了本次修改删除 .native-deps 或重编 OpenSSL。

1. 打开新版，允许通知，保存并启动隧道。
2. 点击“允许后台持续转发”，允许忽略电池优化。
3. 点击“应用设置（后台活动 / 电池 / 流量）”，把本应用设为允许后台活动/不受限制，并允许后台流量。不同厂商名称和位置不同；若有独立冻结/省电设置也需解除。
4. 返回 App，检查电池优化豁免状态；保持运行通知，随后切到 Windows App 访问 127.0.0.1:13389。
5. 从登录、确认远程信息到进入桌面，全程不要切回隧道 App，持续测试至少 5 分钟。再验证停止隧道后本地端口关闭、通知消失，并可重新启动。

## 证据与边界

旧代码本身没有在 Activity.onPause 中停止 C++。用户描述符合后台进程/网络受限的表现，但没有设备日志，尚不能把具体原因定为 Doze、厂商冻结或其他组件。唤醒锁只能阻止 CPU 休眠，不能绕过系统后台网络限制；前台服务也不是对所有厂商省电规则的豁免。

该版本是针对已发现缺口的后台运行加固，未在用户手机上复现和验证修复。不会添加后台自启、开机启动或隐藏服务；停止/强行停止后不会主动拉起。若系统杀死进程，现有 TCP/RDP 会话不能无缝恢复。

若仍有问题，在一次完整失败后复制日志：

- `SERVICE_ALIVE`：主线程仍被调度；观察流量是否增长。
- `SERVICE_TICK_GAP`：服务回调长时间未执行；可能为冻结、休眠或主线程阻塞，不能单凭这一行确定原因。
- `NETWORK_BLOCKED=true`：系统报告应用网络被阻止；false 也不能排除厂商或 VPN 组件的限制。
- `backgroundRestricted`、`batteryExempt` 和 `importance`：记录系统可见的进程/后台权限状态。
- `CPU_WAKE_ACQUIRED` / `CPU_WAKE_RELEASED`：检查服务生命周期资源。

如进程被杀导致界面日志丢失，可连接 ADB 收集：

```powershell
adb logcat -v time -s CFTunnel ActivityManager AndroidRuntime
adb shell dumpsys activity services uk.ayachi.cftunnel
adb shell dumpsys deviceidle whitelist
```

官方背景说明：
- https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions
- https://developer.android.com/training/monitoring-device-state/doze-standby
