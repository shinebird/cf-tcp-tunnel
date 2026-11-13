# 实现依据（核验于 2026-11-10）

- Build update: Android AGP 9.1.1 / Gradle 9.3.1 / JDK 17 compatibility:
  https://developer.android.com/build/releases/agp-9-1-0-release-notes
- Android network permissions / system TLS / background networking:
  https://developer.android.com/develop/connectivity/network-ops/connecting
- Foreground service types, specialUse and subtype declaration:
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Doze and App Standby:
  https://developer.android.com/training/monitoring-device-state/doze-standby
- Android 17 local network permission changes (target 37+):
  https://developer.android.com/privacy-and-security/local-network-permission
- Cloudflare Hibernation and automatic protocol Ping/Pong:
  https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- AdGuard Android per-app routing/filtering:
  https://adguard.com/kb/zh-CN/adguard-for-android/features/app-management/

外部文档说明平台 API，不证明本 App 已经通过 Android APK 构建或实际 Cloudflare/RDP 测试；验证范围以 TEST_REPORT.md 为准。

- API 37 minimum AGP / Android Studio version: https://developer.android.com/build/releases/about-agp

- SDK 37.0 path fix: explicit minor API compileSdk DSL (minorApiLevel = 0):
  https://developer.android.com/build
  https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/CompileSdkReleaseSpec

## 1.0.2 RDP 自检依据

- Microsoft MS-RDPBCGR RDP_NEG_REQ: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpbcgr/902b090b-9cb3-4efc-92bf-ee13373371e3
- Microsoft MS-RDPBCGR RDP_NEG_RSP: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpbcgr/b2975bdc-6d56-49ee-9c57-f2ff3a0b6817

自检不验证完整 RDP 登录。
