#!/usr/bin/env python3
"""Offline STATIC build-configuration checks, not a PowerShell or APK build test."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def text(path):
    return (ROOT / path).read_text(encoding='utf-8-sig')

passed = 0

def check(condition, label):
    global passed
    if not condition:
        raise AssertionError(label)
    passed += 1
    print(f'PASS: {label}')

ps = text('tools/build-apk.ps1')
wrapper = text('tools/build.ps1')
app = text('app/build.gradle')
root = text('build.gradle')
ci = text('.github/workflows/build-android.yml')
sh = text('tools/build.sh')

check("version '9.1.1'" in root, 'AGP pinned to 9.1.1')
check("$Version = '9.3.1'" in ps, 'Windows Gradle pinned to 9.3.1')
check("$AgpVersion = '9.1.1'" in ps, 'Windows guard agrees with root AGP')
check('version=9.3.1' in sh, 'Linux Gradle matches')
check("gradle-version: '9.3.1'" in ci, 'CI Gradle matches')
check('"platforms;android-37.0" "build-tools;37.0.0"' in ci, 'CI installs selected SDK packages')
check("providers.gradleProperty('cfCompileSdk').orElse('37.0')" in app, 'App preserves string SDK property with default 37.0')
check("providers.gradleProperty('cfBuildToolsVersion').orElse('37.0.0')" in app, 'App reads Build Tools property with default 37.0.0')
check('compileSdk = tunnelCompileSdk' in app, 'Legacy major-only SDK configuration retained')
check('version = release(tunnelCompileSdk)' in app and 'minorApiLevel = tunnelCompileSdkMinor' in app, 'Full compileSdk uses official major/minor DSL')
check('tunnelCompileSdkMinor != null' in app, 'Minor zero is configured, not treated as false')
check(".orElse('37.0').get().toInteger()" not in app, 'Dotted SDK string is not converted directly to integer')
check('buildToolsVersion = tunnelBuildTools' in app, 'Build Tools uses selected property')
check(re.search(r'^\s*targetSdk\s+36\s*$', app, re.M) is not None, 'targetSdk remains 36')
check(re.search(r'^\s*minSdk\s+26\s*$', app, re.M) is not None, 'minSdk remains 26')
check('versionCode 3' in app and "versionName '1.0.2'" in app, 'App version is 1.0.2 / 3')
check('sourceCompatibility JavaVersion.VERSION_17' in app, 'Java source level unchanged')
for name, content in [('main script', ps), ('legacy entry', wrapper)]:
    check("[ValidateSet('36', '36.0', '37', '37.0')][string]$CompileSdk = '37.0'" in content, f'{name}: accepts string 37.0 and legacy integer 37')
    check("[string]$BuildToolsVersion = '37.0.0'" in content, f'{name}: Build Tools default')
    check("[string]$ProxyUrl = ''" in content, f'{name}: proxy retained')
    check("[string]$JdkPath = ''" in content, f'{name}: JDK retained')
    check('[switch]$CheckOnly' in content, f'{name}: preflight retained')
check('@PSBoundParameters' in wrapper, 'Legacy entry forwards new parameters')
check('-PcfCompileSdk=$($SdkConfiguration.CompileSdkVersion)' in ps, 'SDK forwarded to Gradle')
check('-PcfBuildToolsVersion=$($SdkConfiguration.BuildToolsVersion)' in ps, 'Build Tools forwarded to Gradle')
check('Invoke-Gradle $GradleBat ($GradleArguments +' in ps, 'Wrapper generation uses shared arguments')
check('$BuildArguments = $GradleArguments +' in ps, 'APK build uses shared arguments')
check('Incomplete SDK37.0 patch' in ps, 'Incomplete patch guard present')
check('@("android-$Major.0", "android-$Major")' in ps, 'Preflight considers dotted and legacy directories')
check('platforms/$Candidate/android.jar' in ps, 'Preflight checks actual candidate android.jar')
check('platforms/$PlatformName/android.jar' in ps, 'Returned android.jar uses selected directory')
check("$PlatformName.Substring('android-'.Length)" in ps, 'Full selected version is propagated')
check('Installed platform folders:' in ps, 'Helpful SDK diagnostics retained')
check('Write-Host "[Platform]' in ps and 'Write-Host "[android.jar]' in ps, 'Preflight prints selected package and path')
check("$AppBuild -notmatch 'minorApiLevel'" in ps, 'Guard catches partially updated Gradle script')
regression = text('tests/test-build-script.ps1')
check('platforms/android-37.0/android.jar' in regression, 'Regression fixture matches real user path')
check('Prefer dotted 37.0 when both' in regression, 'Regression fixture covers both layouts')
check('37.1/extensions/38 do not satisfy' in regression, 'Regression test rejects different minor API')
check('build-tools/$ToolsVersion/aapt2.exe' in ps, 'Build Tools check uses selected version')
check('android-36\\android.jar' not in ps and '35.0.0' not in ps, 'No old fixed SDK path in build script')
check("if ($null -ne $Proxy) { $Request['Proxy'] = $Proxy.Url }" in ps, 'PowerShell proxy forwarding retained')
check('-Dhttp.proxyHost=' in ps and '-Dhttps.proxyHost=' in ps, 'Gradle proxy forwarding retained')
check('Get-FileHash -LiteralPath $Zip -Algorithm SHA256' in ps, 'Download checksum verification retained')
check('suppressUnsupportedCompileSdk' not in text('gradle.properties'), 'No unsupported-SDK warning suppression')
for name in ['tools/build-apk.ps1', 'tools/build.ps1', 'tests/test-build-script.ps1']:
    data = (ROOT / name).read_bytes()
    check(data.startswith(b'\xef\xbb\xbf'), f'{name}: UTF-8 BOM')
    check(b'\r\r\n' not in data and data.count(b'\n') == data.count(b'\r\n'), f'{name}: CRLF preserved')
print(f'OK: {passed} static checks passed. PowerShell/Gradle/Android were not executed by this test.')
