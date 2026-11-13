#requires -Version 5.1
# Offline Windows tests. No SDK, Gradle downloads, real proxy, or real APK build.
[CmdletBinding()]
param()
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$ScriptPath = Join-Path $Project 'tools\build-apk.ps1'
$Passed = 0
$SavedEnvironment = @{}
$Root = Join-Path ([IO.Path]::GetTempPath()) ('cf build test ' + [Guid]::NewGuid().ToString('N'))

function Assert-True {
    param([bool]$Condition, [string]$Label)
    if (-not $Condition) { throw "FAIL: $Label" }
    $script:Passed++
    Write-Host "PASS: $Label"
}
function Assert-Throws {
    param([scriptblock]$Action, [string]$Label)
    $Thrown = $false
    try { & $Action | Out-Null } catch { $Thrown = $true }
    Assert-True $Thrown $Label
}

try {
    foreach ($File in @($ScriptPath, (Join-Path $Project 'tools\build.ps1'))) {
        $Tokens = $null; $Errors = $null
        $Ast = [Management.Automation.Language.Parser]::ParseFile($File, [ref]$Tokens, [ref]$Errors)
        Assert-True ($Errors.Count -eq 0) "PowerShell parser: $([IO.Path]::GetFileName($File))"
        if ($Errors.Count -gt 0) { throw ($Errors | Out-String) }
        if ($File -eq $ScriptPath) { $MainAst = $Ast }
    }
    # Load only the helper function definitions, NOT the main build body.
    foreach ($Definition in $MainAst.FindAll({
        param($Node)
        $Node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $false)) {
        . ([scriptblock]::Create($Definition.Extent.Text))
    }

    $null = New-Item -ItemType Directory -Path $Root -Force
    $Proxy = Get-ProxyConfiguration 'http://127.0.0.1:7897'
    Assert-True ($Proxy.Url -eq 'http://127.0.0.1:7897') 'HTTP proxy URL'
    Assert-True ($Proxy.HostName -eq '127.0.0.1' -and $Proxy.Port -eq 7897) 'Proxy host/port'
    Assert-True ($null -eq (Get-ProxyConfiguration '')) 'No proxy override'
    Assert-True ((Get-ProxyConfiguration 'http://localhost').Port -eq 80) 'Default HTTP port'
    Assert-True ((Get-ProxyConfiguration 'http://[::1]:7897').Port -eq 7897) 'IPv6 proxy'
    foreach ($Bad in @('socks5://127.0.0.1:7897', 'https://127.0.0.1:7897',
        'http://user:pass@localhost:7897', 'http://localhost:7897/path',
        'http://localhost:7897/?x=y', 'http://localhost:0', 'not-a-url')) {
        Assert-Throws { Get-ProxyConfiguration $Bad } "Reject unsupported proxy: $Bad"
    }
    $Jdk = 'D:\Software\IDE\Library\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
    Assert-True ((Quote-CmdArgument $Jdk) -eq ('"' + $Jdk + '"')) 'Quote JDK path with spaces'
    $Bypass = '-Dhttp.nonProxyHosts=localhost|127.*|[::1]'
    Assert-True ((Quote-CmdArgument $Bypass) -eq ('"' + $Bypass + '"')) 'Quote pipe-separated nonProxyHosts'
    Assert-Throws { Quote-CmdArgument 'D:\bad%PATH%\jdk' } 'Reject cmd percent expansion'
    Assert-Throws { Quote-CmdArgument 'bad"value' } 'Reject embedded quote'
    Assert-Throws { Quote-CmdArgument "bad`nvalue" } 'Reject newline'

    $Properties = Join-Path $Root 'local.properties'
    $Sdk = 'D:\Android SDK\' + [char]0x5f00 + [char]0x53d1
    $Encoded = ConvertTo-PropertyValue $Sdk
    Assert-True ($Encoded -match '\\u5f00\\u53d1') 'Unicode Java property encoding'
    [IO.File]::WriteAllText($Properties, "# test`r`nsdk.dir=$Encoded`r`n", [Text.Encoding]::ASCII)
    Assert-True ((Read-SdkDirectory $Properties) -eq $Sdk) 'sdk.dir round trip (backslashes, spaces, Unicode)'
    [IO.File]::AppendAllText($Properties, 'sdk.dir=C\:/second/sdk' + "`r`n", [Text.Encoding]::ASCII)
    Assert-True ((Read-SdkDirectory $Properties) -eq 'C:/second/sdk') 'Last sdk.dir wins'

    # Reproduce the user's SDK layout exactly: android-37.0, not android-37.
    function New-SdkFixtureFile {
        param([string]$SdkHome, [string]$Relative)
        $FullPath = Join-Path $SdkHome $Relative
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $FullPath) -Force
        [IO.File]::WriteAllText($FullPath, 'offline fixture; not executable')
    }
    $FixtureSdk = Join-Path $Root 'Android SDK 37.0 only'
    foreach ($Relative in @('platforms/android-37.0/android.jar',
        'build-tools/37.0.0/aapt2.exe', 'build-tools/37.0.0/d8.bat',
        'build-tools/37.0.0/apksigner.bat', 'build-tools/37.0.0/zipalign.exe')) {
        New-SdkFixtureFile $FixtureSdk $Relative
    }
    $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk
    Assert-True ($Selected.CompileSdk -eq 37) 'Default major API 37'
    Assert-True ($Selected.CompileSdkVersion -ceq '37.0') 'Default preserves full 37.0 version for Gradle'
    Assert-True ($Selected.PlatformPackage -ceq 'platforms;android-37.0') 'Dotted SDK package selected'
    Assert-True ($Selected.PlatformJar -eq (Join-Path $FixtureSdk 'platforms/android-37.0/android.jar')) 'Exact installed android.jar selected'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $FixtureSdk 'platforms/android-37'))) 'No SDK folder renamed/copied/created'
    Assert-True ($Selected.BuildToolsVersion -ceq '37.0.0') 'Default Build Tools 37.0.0'
    Assert-True ($Selected.TargetSdk -eq 36) 'targetSdk stays 36'
    foreach ($Request in @('37', '37.0')) {
        $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel $Request
        Assert-True ($Selected.CompileSdkVersion -ceq '37.0') "Request $Request detects installed .0 package"
    }
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel 36 } 'Missing explicitly selected SDK fails'
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel 38 } 'Unsupported future API fails'
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel '37.1' } 'Unvalidated higher minor version is rejected'
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ToolsVersion '35.0.0' } 'Build Tools below AGP minimum fails'
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ToolsVersion '37.0.0-rc1' } 'Preview tools require explicit future support'

    New-SdkFixtureFile $FixtureSdk 'platforms/android-37/android.jar'
    $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel 37
    Assert-True ($Selected.CompileSdkVersion -ceq '37.0') 'Prefer dotted 37.0 when both complete layouts exist'
    Remove-Item -LiteralPath (Join-Path $FixtureSdk 'platforms/android-37.0/android.jar')
    $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk
    Assert-True ($Selected.CompileSdkVersion -ceq '37') 'Legacy 37 fallback preserves matching Gradle input'
    Assert-True ($Selected.PlatformPackage -ceq 'platforms;android-37') 'Legacy package fallback'
    Remove-Item -LiteralPath (Join-Path $FixtureSdk 'platforms/android-37/android.jar')
    New-SdkFixtureFile $FixtureSdk 'platforms/android-37.1/android.jar'
    New-SdkFixtureFile $FixtureSdk 'platforms/android-37-ext1/android.jar'
    New-SdkFixtureFile $FixtureSdk 'platforms/android-38/android.jar'
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk } '37.1/extensions/38 do not satisfy requested base 37.0'
    $FailureMessage = ''
    try { $null = Get-SdkBuildConfiguration -SdkHome $FixtureSdk } catch { $FailureMessage = $_.Exception.Message }
    Assert-True ($FailureMessage.Contains('android-37.0/android.jar') -and $FailureMessage.Contains('android-37/android.jar')) 'Missing-platform diagnostic shows both checked layouts'
    Assert-True ($FailureMessage.Contains('Installed platform folders:') -and $FailureMessage.Contains('android-37.1')) 'Missing-platform diagnostic lists installed folders'
    New-SdkFixtureFile $FixtureSdk 'platforms/android-37.0/android.jar'
    Remove-Item -LiteralPath (Join-Path $FixtureSdk 'build-tools/37.0.0/zipalign.exe')
    Assert-Throws { Get-SdkBuildConfiguration -SdkHome $FixtureSdk } 'Incomplete Build Tools installation fails'
    foreach ($Relative in @('platforms/android-36/android.jar',
        'build-tools/36.0.0/aapt2.exe', 'build-tools/36.0.0/d8.bat',
        'build-tools/36.0.0/apksigner.bat', 'build-tools/36.0.0/zipalign.exe')) {
        New-SdkFixtureFile $FixtureSdk $Relative
    }
    $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel 36 -ToolsVersion '36.0.0'
    Assert-True ($Selected.CompileSdkVersion -ceq '36' -and $Selected.BuildToolsVersion -ceq '36.0.0') 'Explicit SDK/Build Tools 36 selection'
    Remove-Item -LiteralPath (Join-Path $FixtureSdk 'platforms/android-36/android.jar')
    New-SdkFixtureFile $FixtureSdk 'platforms/android-36.0/android.jar'
    $Selected = Get-SdkBuildConfiguration -SdkHome $FixtureSdk -ApiLevel '36.0' -ToolsVersion '36.0.0'
    Assert-True ($Selected.CompileSdkVersion -ceq '36.0') 'Dotted SDK 36.0 fallback'

    $OldValue = [Environment]::GetEnvironmentVariable('CF_BUILD_TEST_VALUE', 'Process')
    Set-TemporaryEnvironment 'CF_BUILD_TEST_VALUE' 'first'
    Set-TemporaryEnvironment 'CF_BUILD_TEST_VALUE' 'second'
    Assert-True ($env:CF_BUILD_TEST_VALUE -eq 'second') 'Process-local environment assignment'
    Assert-True ($SavedEnvironment['CF_BUILD_TEST_VALUE'] -eq $OldValue) 'Original environment saved once'

    # Mock the network layer: verify proxy arguments are actually forwarded.
    $script:DownloadCalls = @()
    function Invoke-WebRequest {
        param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing,
              [int]$MaximumRedirection, [int]$TimeoutSec, [string]$ErrorAction, [string]$Proxy)
        $script:DownloadCalls += @{ Uri=$Uri; Proxy=$Proxy; Basic=[bool]$UseBasicParsing; Timeout=$TimeoutSec }
        [IO.File]::WriteAllText($OutFile, 'offline-test-response', [Text.Encoding]::ASCII)
    }
    $Destination = Join-Path $Root 'mock-download.txt'
    Download-File 'https://example.invalid/gradle.zip' $Destination $Proxy
    Assert-True ([IO.File]::ReadAllText($Destination) -eq 'offline-test-response') 'Atomic download placement (mock)'
    Assert-True ($DownloadCalls[0].Proxy -eq 'http://127.0.0.1:7897') 'Download gets explicit proxy (mock)'
    Assert-True $DownloadCalls[0].Basic 'UseBasicParsing enabled (mock)'
    Download-File 'https://example.invalid/sha256' $Destination $null 60
    Assert-True ([string]::IsNullOrEmpty($DownloadCalls[1].Proxy)) 'No forced proxy when omitted (mock)'
    Assert-True ($DownloadCalls[1].Timeout -eq 60) 'Checksum download timeout (mock)'
    Assert-True (@(Get-ChildItem -LiteralPath $Root -Filter '*.part').Count -eq 0) 'No partial download files left'

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        # Exercise REAL cmd.exe/.bat argument passing with fake Gradle and no Java/SDK.
        $Helper = Join-Path $Root 'capture arguments.ps1'
        $FakeBat = Join-Path $Root 'fake gradle.bat'
        $Captured = Join-Path $Root 'arguments.json'
        $PSExe = Join-Path $PSHOME 'powershell.exe'
        if (-not (Test-Path -LiteralPath $PSExe)) { $PSExe = Join-Path $PSHOME 'pwsh.exe' }
        Set-TemporaryEnvironment 'CF_TEST_PS' $PSExe
        Set-TemporaryEnvironment 'CF_TEST_HELPER' $Helper
        Set-TemporaryEnvironment 'CF_TEST_OUT' $Captured
        $HelperText = '@($env:CF_ARG1, $env:CF_ARG2, $env:CF_ARG3, $env:CF_ARG4) | ConvertTo-Json | Set-Content -LiteralPath $env:CF_TEST_OUT -Encoding UTF8'
        [IO.File]::WriteAllText($Helper, $HelperText, [Text.Encoding]::ASCII)
        $BatText = "@echo off`r`nsetlocal`r`nset `"CF_ARG1=%~1`"`r`nset `"CF_ARG2=%~2`"`r`nset `"CF_ARG3=%~3`"`r`nset `"CF_ARG4=%~4`"`r`n`"%CF_TEST_PS%`" -NoProfile -ExecutionPolicy Bypass -File `"%CF_TEST_HELPER%`"`r`nexit /b %errorlevel%`r`n"
        [IO.File]::WriteAllText($FakeBat, $BatText, [Text.Encoding]::ASCII)
        $Expected = @('--no-daemon', "-Dorg.gradle.java.home=$Jdk", $Bypass, ':app:assembleDebug')
        Invoke-Gradle $FakeBat $Expected
        $Actual = @(Get-Content -LiteralPath $Captured -Raw | ConvertFrom-Json)
        Assert-True ($Actual.Count -eq $Expected.Count) 'cmd.exe preserves argument count'
        for ($i = 0; $i -lt $Expected.Count; $i++) {
            Assert-True ($Actual[$i] -eq $Expected[$i]) "cmd.exe argument $i round trip"
        }
        [IO.File]::WriteAllText($FakeBat, "@exit /b 17`r`n", [Text.Encoding]::ASCII)
        Assert-Throws { Invoke-Gradle $FakeBat @('wrapper') } 'Nonzero Gradle exit code is an error'
    }
    else { Write-Warning 'Windows batch tests skipped: this is not Windows.' }
    Write-Host "OK: $Passed assertions passed. No APK was built."
}
finally {
    foreach ($Name in $SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($Name, $SavedEnvironment[$Name], 'Process')
    }
    if (Test-Path -LiteralPath $Root) { Remove-Item -LiteralPath $Root -Recurse -Force }
}
