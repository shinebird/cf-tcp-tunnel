> 历史 1.1.0 移植记录。构建说明已由 LOCAL_SOURCES.md 的 local-direct-r2 取代，其中的旧依赖版本/工具描述不再适用。

# Official implementation references (checked for this port)

- Android NDK + JNI + CMake integration: https://developer.android.com/studio/projects/add-native-code
- Native threads/JNIEnv lifetime: https://developer.android.com/ndk/guides/jni-tips
- NDK installation: https://developer.android.com/studio/projects/install-ndk
- vcpkg Android triplets/toolchain: https://learn.microsoft.com/en-us/vcpkg/users/platforms/android
- Pinned registry: https://github.com/microsoft/vcpkg/tree/9eec002df1cd109f8f104cd966a763daf9751489
- OpenSSL Android cross-build port (make/Perl): https://github.com/microsoft/vcpkg/blob/2026.10.16/ports/openssl/unix/portfile.cmake
- 16 KiB native library + APK alignment: https://developer.android.com/guide/practices/page-sizes
- Asio platform-specific backends: https://www.boost.org/doc/libs/latest/doc/html/boost_asio/overview/implementation.html
- Android Doze: https://developer.android.com/training/monitoring-device-state/doze-standby

These references describe APIs/build mechanisms; they do not establish that the supplied APK has been built or tested on a phone.
