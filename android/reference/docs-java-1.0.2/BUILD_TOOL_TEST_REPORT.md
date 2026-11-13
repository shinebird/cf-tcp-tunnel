# SDK 37.0 path fix — validation report

## Scope

Based on cf-tcp-tunnel-android-1.0.1-sdk37.zip. The old preflight searched only
platforms/android-37/android.jar; the user has platforms/android-37.0/android.jar.
The fixed script accepts the string 37.0 (and the legacy 37 argument), prefers
the existing dotted platform directory, and preserves its version for Gradle.
app/build.gradle uses compileSdk.release with minorApiLevel=0 for the full version.
CI installs platforms;android-37.0. No SDK files or package metadata are changed.

AGP 9.1.1, Gradle 9.3.1, Build Tools 37.0.0, recommended JDK 17, targetSdk 36,
minSdk 26 and App version 1.0.1 / versionCode 2 stay unchanged.
All app/src files and app/lint.xml are byte-for-byte identical to the input archive.
The Worker, C++ client and Android networking implementation were not modified.

## Executed in this environment

- python tests/check_build_config.py: 55 STATIC source-configuration assertions passed.
  Checks string SDK parsing, full version forwarding, explicit minor-API DSL,
  dotted/legacy candidates, CI package name, incomplete patch guard, proxy/JDK
  retention, PowerShell BOM/CRLF, target/min SDK and app version.
  These assertions do not execute PowerShell, the AGP DSL, or an Android build.
- bash -n tools/build.sh: passed.
- PowerShell lexical inspection: no error tokens in the three .ps1 files.
  This is NOT the PowerShell parser or runtime.
- python tests/run_core_tests.py: 85 Java core assertions passed; OpenJDK 21
  compiled the unchanged pure Java core using --release 17.
- Input/output byte comparison: no app source, resource or Manifest changes.
- Archive integrity, patched file set and generated SHA-256 manifests checked.

## Regression tests supplied but NOT executed here

Windows tests/test-build-script.ps1 contains filesystem fixtures for:
- Only platforms/android-37.0/android.jar installed (the reported failure).
- Both -ApiLevel 37 and -ApiLevel '37.0' resolving to the dotted package.
- Dotted and legacy directories both present: prefer the dotted complete one.
- Only legacy android-37 complete: preserve legacy Gradle version selection.
- Missing android.jar; 37.1, extensions or 38 must not satisfy requested base API.
- Diagnostics containing both candidate paths and installed platform folders.
- Missing Build Tools files, SDK 36 and 36.0 fallback, invalid versions.
Existing proxy, property-encoding, download-mock and cmd.exe tests are retained.

Run locally:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\test-build-script.ps1
```

## Limitations

This environment has no Windows/PowerShell, Android SDK or Gradle distribution.
Attempts to download the tools from the container failed DNS/network access.
No Windows runtime test, live proxy test, Gradle configuration execution,
Android APK build, Lint, emulator/phone or real Cloudflare/RDP test was performed
for this update. No APK is included. Historical WSS tests were not rerun.

## Verified official documentation

- https://developer.android.com/build — compileSdk full major/minor DSL.
- https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/CompileSdkReleaseSpec
  — minorApiLevel, available since AGP 8.13.0, including in AGP 9.1.
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
  — AGP 9.1.1 supports API 37.0; Gradle 9.3.1 and JDK 17.
