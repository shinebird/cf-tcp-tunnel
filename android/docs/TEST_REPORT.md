> 历史 1.1.0 移植记录。构建说明已由 LOCAL_SOURCES.md 的 local-direct-r2 取代，其中的旧依赖版本/工具描述不再适用。

# Validation performed for 1.1.0 Native

Executed in this environment:
- Native core + JNI CMake build on Linux x86_64 with GCC 14.2, Boost 1.83, OpenSSL 3.5.7: PASS.
- NativeTunnel/Config/RdpProbe/Harness Java compilation (`--release 17`, host JDK 21): PASS.
- 7 real TCP/TLS/WSS integration scenarios invoking the .so through JNI: PASS. See tests/native-test-result.txt.
- 10 migration/provenance static tests: PASS.
- 8 version-qualified theme resource tests: PASS.
- Bash script parsing: PASS.

Not executed:
- Android NDK cross-compilation or native dependency build.
- Windows PowerShell script execution / Windows vcpkg dependency build.
- Gradle/Android Lint/Android APK build.
- Real Android hardware, 16KiB device execution, actual Cloudflare Worker, Windows RDP/CredSSP.
- The GitHub Actions workflow in the user's repository.

No host .so, .class file or APK is shipped in this source archive. No assertion that the previous Android RDP problem is fixed.
