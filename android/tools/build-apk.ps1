#requires -Version 5.1
<#
.SYNOPSIS
Build this Android project with SDK 37.0, an explicit HTTP proxy and JDK.
.EXAMPLE
.\tools\build-apk.ps1 -ProxyUrl 'http://127.0.0.1:7897' -JdkPath 'D:\Java\jdk-17'
.EXAMPLE
.\tools\build-apk.ps1 -JdkPath 'D:\Java\jdk-17' -AndroidSdkPath 'D:\Android\Sdk' -CheckOnly
.EXAMPLE
.\tools\build-apk.ps1 -JdkPath 'D:\Java\jdk-17' -CompileSdk '37.0' -BuildToolsVersion '37.0.0'
.NOTES
Windows PowerShell 5.1 / PowerShell 7 on Windows. No administrator rights needed.
HTTP proxy only (HTTP CONNECT also handles HTTPS destinations). No proxy credentials.
All environment changes are process-local and restored in finally. local.properties
is temporarily updated for SDK selection and restored byte-for-byte on normal exit/error.
Gradle 9.3.1 / AGP 9.1.1 support compileSdk 37.0 with JDK 17.
Defaults: compileSdk 37.0, Build Tools 37.0.0. targetSdk stays 36 and minSdk stays 26.
Recognizes android-37.0 and legacy android-37 without renaming SDK directories.
Apply the whole SDK37.0 patch, including app/build.gradle, not just this script.
#>
[CmdletBinding()]
param(
    [string]$ProxyUrl = '',
    [string]$JdkPath = '',
    [Alias('SdkPath')][string]$AndroidSdkPath = '',
    [ValidateSet('36', '36.0', '37', '37.0')][string]$CompileSdk = '37.0',
    [ValidatePattern('^(36|37)\.\d+\.\d+$')][string]$BuildToolsVersion = '37.0.0',
    [string]$NdkPath = '',
    [string]$NdkVersion = '30.0.16248370',
    [string]$CmakeVersion = '4.1.2',
    [string]$NativeAbis = 'arm64-v8a',
    [string]$MsysRoot = '',
    [string]$NativeWorkRoot = '',
    [ValidateRange(1,128)][int]$NativeJobs = 4,
    [string]$BoostSource = '',
    [string]$OpenSSLSource = '',
    [string]$PythonPath = 'python',
    [switch]$CheckOnly,
    [switch]$SkipLint,
    [switch]$RefreshDependencies
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$Version = '9.3.1'
$AgpVersion = '9.1.1'
$Cache = Join-Path $Root '.gradle-dist'
$LocalProperties = Join-Path $Root 'local.properties'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$SavedEnvironment = @{}
$LocalPropertiesChanged = $false
$HadLocalProperties = $false
$LocalPropertiesBytes = $null
$Lock = $null
$OldLocation = Get-Location
$OldProgress = $ProgressPreference
$OldTls = [Net.ServicePointManager]::SecurityProtocol

function Set-TemporaryEnvironment {
    param([string]$Name, [AllowNull()][string]$Value)
    if (-not $SavedEnvironment.ContainsKey($Name)) {
        $SavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Resolve-Directory {
    param([string]$Path, [string]$Label)
    $Expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    if ([string]::IsNullOrWhiteSpace($Expanded)) { throw "$Label path is empty." }
    $Item = Get-Item -LiteralPath $Expanded -ErrorAction Stop
    if (-not $Item.PSIsContainer -or $Item.PSProvider.Name -ne 'FileSystem') {
        throw "$Label must point to a directory: $Expanded"
    }
    return $Item.FullName
}

function Get-ProxyConfiguration {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $Address = $null
    if (-not [Uri]::TryCreate($Text.Trim(), [UriKind]::Absolute, [ref]$Address)) {
        throw 'Invalid -ProxyUrl. Example: http://127.0.0.1:7897'
    }
    if ($Address.Scheme -ne 'http' -or [string]::IsNullOrEmpty($Address.Host)) {
        throw '-ProxyUrl must use http://host:port (HTTP or mixed proxy port, not SOCKS-only). HTTPS downloads use HTTP CONNECT.'
    }
    if ($Address.Port -lt 1 -or $Address.Port -gt 65535 -or
        $Address.UserInfo -or $Address.Query -or $Address.Fragment -or
        $Address.AbsolutePath -ne '/') {
        throw 'Use a plain HTTP proxy address without credentials, path, query or fragment.'
    }
    return [pscustomobject]@{
        Url = $Address.GetLeftPart([UriPartial]::Authority)
        HostName = $Address.DnsSafeHost
        Port = $Address.Port
    }
}

function Get-JavaInformation {
    param([string]$JavaExe)
    # ProcessStartInfo avoids PowerShell 5.1 treating java -version stderr as an error.
    $Start = New-Object Diagnostics.ProcessStartInfo
    $Start.FileName = $JavaExe
    $Start.Arguments = '-XshowSettings:properties -version'
    $Start.UseShellExecute = $false
    $Start.RedirectStandardOutput = $true
    $Start.RedirectStandardError = $true
    $Start.CreateNoWindow = $true
    $Process = New-Object Diagnostics.Process
    $Process.StartInfo = $Start
    try {
        if (-not $Process.Start()) { throw "Could not start $JavaExe" }
        $StdOut = $Process.StandardOutput.ReadToEndAsync()
        $StdErr = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit(20000)) {
            $Process.Kill()
            throw 'java -version timed out after 20 seconds.'
        }
        $Text = $StdOut.GetAwaiter().GetResult() + "`n" + $StdErr.GetAwaiter().GetResult()
        if ($Process.ExitCode -ne 0) { throw "JDK probe failed: $Text" }
        $HomeMatch = [regex]::Match($Text, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
        $VersionMatch = [regex]::Match($Text, '(?m)^\s*java\.specification\.version\s*=\s*(\S+)')
        $FullVersionMatch = [regex]::Match($Text, '(?m)^\s*java\.version\s*=\s*(\S+)')
        if (-not $HomeMatch.Success -or -not $VersionMatch.Success) {
            throw "Cannot determine JDK version/home from: $JavaExe"
        }
        $Spec = $VersionMatch.Groups[1].Value
        $Major = if ($Spec.StartsWith('1.')) { [int]$Spec.Split('.')[1] } else { [int]$Spec.Split('.')[0] }
        return [pscustomobject]@{
            Home = $HomeMatch.Groups[1].Value.Trim()
            Major = $Major
            Version = $FullVersionMatch.Groups[1].Value
        }
    }
    finally { $Process.Dispose() }
}

function Read-SdkDirectory {
    param([string]$PropertiesPath)
    if (-not (Test-Path -LiteralPath $PropertiesPath -PathType Leaf)) { return '' }
    # Java .properties uses ISO-8859-1 with optional \uXXXX escapes.
    $Text = [IO.File]::ReadAllText($PropertiesPath, [Text.Encoding]::GetEncoding(28591))
    $MatchesFound = [regex]::Matches($Text, '(?m)^\s*sdk\.dir\s*[=:]\s*([^\r\n]*)')
    if ($MatchesFound.Count -eq 0) { return '' }
    $Value = $MatchesFound[$MatchesFound.Count - 1].Groups[1].Value.Trim()
    if ([regex]::IsMatch($Value, '(?<!\\)(?:\\\\)*\\$')) {
        throw 'Multi-line sdk.dir is not supported by this script. Use -AndroidSdkPath explicitly.'
    }
    return [regex]::Replace($Value, '\\u([0-9a-fA-F]{4})|\\(.)', {
        param($Match)
        if ($Match.Groups[1].Success) {
            return [string][char][Convert]::ToInt32($Match.Groups[1].Value, 16)
        }
        switch -CaseSensitive ($Match.Groups[2].Value) {
            't' { return "`t" }; 'r' { return "`r" }; 'n' { return "`n" }; 'f' { return "`f" }
            default { return $Match.Groups[2].Value }
        }
    })
}

function Get-SdkBuildConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$SdkHome,
        [ValidateSet('36', '36.0', '37', '37.0')][string]$ApiLevel = '37.0',
        [ValidatePattern('^(36|37)\.\d+\.\d+$')][string]$ToolsVersion = '37.0.0'
    )
    # A base API can use a major-only or an explicit .0 SDK package name.
    # The API 37 installer uses android-37.0. Never rename SDK-managed folders.
    # Do not pick android-37.1, android-37-ext*, previews, or another major API.
    $Major = [int]($ApiLevel.Split('.')[0])
    $Candidates = if ($Major -ge 37) {
        @("android-$Major.0", "android-$Major")
    } else {
        @("android-$Major", "android-$Major.0")
    }
    $PlatformName = $null
    foreach ($Candidate in $Candidates) {
        if (Test-Path -LiteralPath (Join-Path $SdkHome "platforms/$Candidate/android.jar") -PathType Leaf) {
            $PlatformName = $Candidate
            break
        }
    }
    $Required = @(
        "build-tools/$ToolsVersion/aapt2.exe",
        "build-tools/$ToolsVersion/d8.bat",
        "build-tools/$ToolsVersion/apksigner.bat",
        "build-tools/$ToolsVersion/zipalign.exe"
    )
    $Missing = @($Required | Where-Object {
        -not (Test-Path -LiteralPath (Join-Path $SdkHome $_) -PathType Leaf)
    })
    if ($null -eq $PlatformName) {
        $Expected = @($Candidates | ForEach-Object { "platforms/$_/android.jar" }) -join ' OR '
        $Missing = @($Expected) + $Missing
    }
    if ($Missing.Count -gt 0) {
        $PlatformsHome = Join-Path $SdkHome 'platforms'
        $Installed = if (Test-Path -LiteralPath $PlatformsHome -PathType Container) {
            @(Get-ChildItem -LiteralPath $PlatformsHome -Directory | Sort-Object Name | ForEach-Object { $_.Name }) -join ', '
        } else { '' }
        if ([string]::IsNullOrWhiteSpace($Installed)) { $Installed = '(none)' }
        throw ("Missing SDK component(s): " + ($Missing -join ', ') +
            ". Requested base API=$Major.0; Build Tools=$ToolsVersion. Installed platform folders: $Installed. " +
            "Check SDK Manager or -AndroidSdkPath. SDK=$SdkHome")
    }
    # Pass the full selected version into Gradle. Converting 37.0 to an int here
    # would discard the minor API again even though the preflight just found it.
    $SelectedVersion = $PlatformName.Substring('android-'.Length)
    return [pscustomobject]@{
        CompileSdk = $Major
        CompileSdkVersion = $SelectedVersion
        TargetSdk = 36
        BuildToolsVersion = $ToolsVersion
        PlatformName = $PlatformName
        PlatformPackage = "platforms;$PlatformName"
        PlatformJar = Join-Path $SdkHome "platforms/$PlatformName/android.jar"
        ToolsDirectory = Join-Path $SdkHome "build-tools/$ToolsVersion"
    }
}

function ConvertTo-PropertyValue {
    param([string]$Value)
    $Builder = New-Object Text.StringBuilder
    foreach ($Character in $Value.ToCharArray()) {
        $Code = [int]$Character
        if ($Character -eq '\') { [void]$Builder.Append('\\') }
        elseif ($Character -eq ':' -or $Character -eq '=' -or $Character -eq ' ') {
            [void]$Builder.Append('\').Append($Character)
        }
        elseif ($Code -lt 32 -or $Code -gt 126) {
            [void]$Builder.Append(('\u{0:x4}' -f $Code))
        }
        else { [void]$Builder.Append($Character) }
    }
    return $Builder.ToString()
}

function Quote-CmdArgument {
    param([string]$Value)
    # Every argument is quoted before entering cmd.exe. Pipes in nonProxyHosts
    # must NOT become shell pipelines. Percent expansion cannot safely be escaped
    # across both cmd /c and a .bat file; reject that rare path character explicitly.
    if ($Value.IndexOfAny([char[]]"`r`n`"%") -ge 0) {
        throw 'A build path/argument contains a quote, newline or percent sign. Move the project/JDK/SDK to a path without these characters.'
    }
    return '"' + $Value + '"'
}

function Invoke-Gradle {
    param([string]$GradleBat, [string[]]$Arguments)
    $Quoted = @((Quote-CmdArgument $GradleBat))
    foreach ($Argument in $Arguments) { $Quoted += (Quote-CmdArgument $Argument) }
    $Start = New-Object Diagnostics.ProcessStartInfo
    $Start.FileName = $env:ComSpec
    $Start.Arguments = '/d /v:off /s /c "' + ($Quoted -join ' ') + '"'
    $Start.UseShellExecute = $false
    $Start.WorkingDirectory = $Root
    # Inherit the console and the temporary environment, with no stdout buffering.
    $Process = New-Object Diagnostics.Process
    $Process.StartInfo = $Start
    try {
        if (-not $Process.Start()) { throw 'Cannot start Gradle.' }
        $Process.WaitForExit()
        if ($Process.ExitCode -ne 0) {
            throw "Gradle failed (exit code $($Process.ExitCode)). Read the first build error above."
        }
    }
    finally { $Process.Dispose() }
}

function Download-File {
    param([string]$Url, [string]$Destination, $Proxy, [int]$TimeoutSeconds = 600)
    $Part = $Destination + '.' + [Guid]::NewGuid().ToString('N') + '.part'
    try {
        for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
            try {
                $Request = @{
                    Uri = $Url; OutFile = $Part; UseBasicParsing = $true
                    MaximumRedirection = 10; TimeoutSec = $TimeoutSeconds; ErrorAction = 'Stop'
                }
                if ($null -ne $Proxy) { $Request['Proxy'] = $Proxy.Url }
                Write-Host "[Download $Attempt/3] $Url"
                Invoke-WebRequest @Request
                Move-Item -LiteralPath $Part -Destination $Destination -Force
                return
            }
            catch {
                if (Test-Path -LiteralPath $Part) { Remove-Item -LiteralPath $Part -Force }
                if ($Attempt -eq 3) { throw }
                Write-Warning "Download failed; retrying with the same proxy: $($_.Exception.Message)"
                Start-Sleep -Seconds (2 * $Attempt)
            }
        }
    }
    finally {
        if (Test-Path -LiteralPath $Part) { Remove-Item -LiteralPath $Part -Force }
    }
}

try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'This script targets Windows. On Linux/macOS use tools/build.sh.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Root 'settings.gradle'))) {
        throw 'Place this script in the Android project tools directory, next to the existing build.ps1.'
    }
    $Proxy = Get-ProxyConfiguration $ProxyUrl

    # Explicit -JdkPath always wins, then JAVA_HOME, then java.exe from PATH.
    if (-not [string]::IsNullOrWhiteSpace($JdkPath)) {
        $JdkHome = Resolve-Directory $JdkPath 'JDK'
    }
    elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JdkHome = Resolve-Directory $env:JAVA_HOME 'JAVA_HOME'
    }
    else {
        $JavaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $JavaCommand) { throw 'No JDK found. Specify -JdkPath pointing to a JDK 17 directory.' }
        $Probe = Get-JavaInformation $JavaCommand.Source
        $JdkHome = Resolve-Directory $Probe.Home 'JDK'
    }
    foreach ($Tool in @('java.exe', 'javac.exe', 'jar.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $JdkHome "bin\$Tool") -PathType Leaf)) {
            throw "JDK is incomplete: missing bin\$Tool under $JdkHome. Specify a JDK root, not bin or a JRE."
        }
    }
    $Java = Get-JavaInformation (Join-Path $JdkHome 'bin\java.exe')
    if ($Java.Major -lt 17 -or $Java.Major -gt 23) {
        throw "Detected Java $($Java.Version). This AGP 9.1.1 / Gradle 9.3.1 build script accepts JDK 17-23; JDK 17 is recommended. Use -JdkPath."
    }
    if ($Java.Major -ne 17) { Write-Warning "Using Java $($Java.Version); JDK 17 is the reference build configuration." }
    if (Test-Path -LiteralPath (Join-Path $Root 'gradle\gradle-daemon-jvm.properties')) {
        throw 'gradle/gradle-daemon-jvm.properties overrides JDK selection. Remove or intentionally update that file before using -JdkPath; the original project does not include it.'
    }

    if (-not [string]::IsNullOrWhiteSpace($AndroidSdkPath)) { $SdkCandidate = $AndroidSdkPath }
    else {
        $SdkCandidate = Read-SdkDirectory $LocalProperties
        if (-not [string]::IsNullOrWhiteSpace($SdkCandidate) -and -not [IO.Path]::IsPathRooted($SdkCandidate)) {
            $SdkCandidate = Join-Path $Root $SdkCandidate
        }
        if ([string]::IsNullOrWhiteSpace($SdkCandidate)) { $SdkCandidate = $env:ANDROID_HOME }
        if ([string]::IsNullOrWhiteSpace($SdkCandidate)) { $SdkCandidate = $env:ANDROID_SDK_ROOT }
        if ([string]::IsNullOrWhiteSpace($SdkCandidate)) {
            if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'Specify -AndroidSdkPath.' }
            $SdkCandidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        }
    }
    $SdkHome = Resolve-Directory $SdkCandidate 'Android SDK'
    $SdkConfiguration = Get-SdkBuildConfiguration -SdkHome $SdkHome -ApiLevel $CompileSdk -ToolsVersion $BuildToolsVersion
    # Detect an incomplete patch before downloading Gradle or Maven dependencies.
    $ProjectBuild = [IO.File]::ReadAllText((Join-Path $Root 'build.gradle'))
    $AppBuild = [IO.File]::ReadAllText((Join-Path $Root 'app/build.gradle'))
    if ($ProjectBuild -notmatch ("version\s+['`"]" + [regex]::Escape($AgpVersion) + "['`"]") -or
        $AppBuild -notmatch 'cfCompileSdk' -or $AppBuild -notmatch 'cfBuildToolsVersion' -or
        $AppBuild -notmatch 'minorApiLevel') {
        throw 'Incomplete SDK37.0 patch. Also replace build.gradle and app/build.gradle from the patch; do not update only tools/build-apk.ps1.'
    }
    # Fail early for cmd.exe-incompatible paths rather than after downloads.
    foreach ($PathToCheck in @($Root, $JdkHome, $SdkHome)) { $null = Quote-CmdArgument $PathToCheck }

    Set-TemporaryEnvironment 'JAVA_HOME' $JdkHome
    Set-TemporaryEnvironment 'PATH' ((Join-Path $JdkHome 'bin') + ';' + $env:PATH)
    Set-TemporaryEnvironment 'ANDROID_HOME' $SdkHome
    Set-TemporaryEnvironment 'ANDROID_SDK_ROOT' $SdkHome
    $GradleArguments = @(
        '--no-daemon', '--console=plain', '--stacktrace',
        "-Dorg.gradle.java.home=$($JdkHome.Replace('\','/'))",
        "-PcfCompileSdk=$($SdkConfiguration.CompileSdkVersion)",
        "-PcfBuildToolsVersion=$($SdkConfiguration.BuildToolsVersion)"
    )
    if ($null -ne $Proxy) {
        foreach ($Name in @('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY')) { Set-TemporaryEnvironment $Name $Proxy.Url }
        Set-TemporaryEnvironment 'NO_PROXY' 'localhost,127.0.0.1,::1'
        $GradleArguments += @(
            "-Dhttp.proxyHost=$($Proxy.HostName)", "-Dhttp.proxyPort=$($Proxy.Port)",
            "-Dhttps.proxyHost=$($Proxy.HostName)", "-Dhttps.proxyPort=$($Proxy.Port)",
            '-Dhttp.nonProxyHosts=localhost|127.*|[::1]', '-DsocksProxyHost=',
            '-Dhttp.proxyUser=', '-Dhttp.proxyPassword=', '-Dhttps.proxyUser=', '-Dhttps.proxyPassword=',
            '-Djava.net.useSystemProxies=false'
        )
    }
    Write-Host "[JDK]     $JdkHome"
    Write-Host "[Java]    $($Java.Version)"
    Write-Host "[SDK]     $SdkHome"
    Write-Host "[Android] compileSdk=$($SdkConfiguration.CompileSdkVersion); Build Tools=$($SdkConfiguration.BuildToolsVersion); targetSdk=36; minSdk=26"
    Write-Host "[Platform] $($SdkConfiguration.PlatformPackage)"
    Write-Host "[android.jar] $($SdkConfiguration.PlatformJar)"
    Write-Host "[AGP]     $AgpVersion"
    Write-Host "[Gradle]  $Version (project-local distribution; --no-daemon)"
    if ($null -ne $Proxy) { Write-Host "[Proxy]   $($Proxy.Url) (PowerShell downloads + Gradle HTTP/HTTPS)" }
    else { Write-Host '[Proxy]   No override; existing OS/Gradle settings may still apply.' }
    $NativeArguments = @{
        AndroidSdkPath=$SdkHome; NdkPath=$NdkPath; NdkVersion=$NdkVersion;
        CmakeVersion=$CmakeVersion; NativeAbis=$NativeAbis; MsysRoot=$MsysRoot; NativeWorkRoot=$NativeWorkRoot; NativeJobs=$NativeJobs; ProxyUrl=$ProxyUrl;
        BoostSource=$BoostSource; OpenSSLSource=$OpenSSLSource; PythonPath=$PythonPath
    }
    & (Join-Path $PSScriptRoot 'prepare-native.ps1') @NativeArguments -CheckOnly
    $NativeHome = if ($NdkPath) { Resolve-Directory $NdkPath 'NDK' } else { Join-Path $SdkHome "ndk\$NdkVersion" }
    $NdkProps = [IO.File]::ReadAllText((Join-Path $NativeHome 'source.properties'))
    $NdkRevisionMatch = [regex]::Match($NdkProps, '(?m)^Pkg\.Revision\s*=\s*([0-9.]+)')
    if (-not $NdkRevisionMatch.Success) { throw 'Cannot read NDK revision.' }
    $GradleArguments += @(
        "-PcfNdkPath=$($NativeHome.Replace('\','/'))",
        "-PcfNdkVersion=$($NdkRevisionMatch.Groups[1].Value)",
        "-PcfCmakeVersion=$CmakeVersion", "-PcfAbis=$NativeAbis",
        "-PcfNativeDeps=$($Root.Replace('\','/'))/.native-deps/installed-direct"
    )
    $GradleArguments += "-PcfBoostSource=$(if($BoostSource){(Resolve-Directory $BoostSource 'Boost source').Replace('\','/')}else{''})"
    if ($CheckOnly) {
        Write-Host '[OK] Local configuration checked. No downloads/build or proxy connectivity test performed.'
        return
    }

    $null = New-Item -ItemType Directory -Path $Cache -Force
    # Prevent two copies of this script from concurrently replacing local.properties.
    try { $Lock = [IO.File]::Open((Join-Path $Cache 'windows-build.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another build-apk.ps1 invocation may be running for this project. Wait for it to finish.' }

    $HadLocalProperties = Test-Path -LiteralPath $LocalProperties -PathType Leaf
    if ($HadLocalProperties) { $LocalPropertiesBytes = [IO.File]::ReadAllBytes($LocalProperties) }
    # Appending a final sdk.dir overrides any existing one without deleting other SDK settings.
    $OldPropertiesText = if ($HadLocalProperties) { [Text.Encoding]::GetEncoding(28591).GetString($LocalPropertiesBytes) } else { '' }
    $SdkProperty = ConvertTo-PropertyValue ($SdkHome.Replace('\', '/'))
    $LocalPropertiesChanged = $true
    [IO.File]::WriteAllText($LocalProperties, $OldPropertiesText + "`r`n`r`nsdk.dir=$SdkProperty`r`n", [Text.Encoding]::GetEncoding(28591))

    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = $OldTls -bor [Net.SecurityProtocolType]::Tls12
    $Url = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
    $Zip = Join-Path $Cache "gradle-$Version-bin.zip"
    $ChecksumFile = $Zip + '.sha256'
    $GradleHome = Join-Path $Cache "gradle-$Version"
    $GradleBat = Join-Path $GradleHome 'bin\gradle.bat'
    $Expected = ''
    if (Test-Path -LiteralPath $ChecksumFile) { $Expected = [IO.File]::ReadAllText($ChecksumFile).Trim().ToLowerInvariant() }
    if ($Expected -notmatch '^[0-9a-f]{64}$') {
        Download-File ($Url + '.sha256') $ChecksumFile $Proxy 60
        $Expected = [IO.File]::ReadAllText($ChecksumFile).Trim().ToLowerInvariant()
        if ($Expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid Gradle checksum response. Check proxy/network interception.' }
    }
    if (-not (Test-Path -LiteralPath $GradleBat -PathType Leaf)) {
        if (Test-Path -LiteralPath $Zip) {
            if ((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
                Write-Warning 'Removing an incomplete or mismatched cached Gradle ZIP.'
                Remove-Item -LiteralPath $Zip -Force
            }
        }
        if (-not (Test-Path -LiteralPath $Zip)) { Download-File $Url $Zip $Proxy }
        if ((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
            Remove-Item -LiteralPath $Zip -Force
            throw 'Gradle SHA-256 mismatch. Download deleted; TLS and checksum validation remain enabled.'
        }
        $Extract = Join-Path $Cache ('extract-' + [Guid]::NewGuid().ToString('N'))
        try {
            Expand-Archive -LiteralPath $Zip -DestinationPath $Extract -Force
            $ExtractedHome = Join-Path $Extract "gradle-$Version"
            if (-not (Test-Path -LiteralPath (Join-Path $ExtractedHome 'bin\gradle.bat'))) { throw 'Invalid Gradle distribution layout.' }
            if (Test-Path -LiteralPath $GradleHome) { Remove-Item -LiteralPath $GradleHome -Recurse -Force }
            Move-Item -LiteralPath $ExtractedHome -Destination $GradleHome
        }
        finally { if (Test-Path -LiteralPath $Extract) { Remove-Item -LiteralPath $Extract -Recurse -Force } }
    }

    & (Join-Path $PSScriptRoot 'prepare-native.ps1') @NativeArguments

    # Generate the genuine Wrapper in a dependency-free project. This avoids resolving
    # AGP merely to create gradlew, and never adds proxy settings to committed files.
    $Bootstrap = Join-Path $Cache 'wrapper-bootstrap'
    $null = New-Item -ItemType Directory -Path $Bootstrap -Force
    [IO.File]::WriteAllText((Join-Path $Bootstrap 'settings.gradle'), "rootProject.name = 'wrapper-bootstrap'`n", $Utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $Bootstrap 'build.gradle'), '', $Utf8NoBom)
    Write-Host '[Build] Generating the official Gradle Wrapper...'
    Invoke-Gradle $GradleBat ($GradleArguments + @('--project-dir', $Bootstrap, 'wrapper',
        '--gradle-version', $Version, '--distribution-type', 'bin',
        '--gradle-distribution-sha256-sum', $Expected, '--network-timeout', '60000'))
    foreach ($RelativePath in @('gradlew', 'gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.properties')) {
        $Destination = Join-Path $Root $RelativePath
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force
        Copy-Item -LiteralPath (Join-Path $Bootstrap $RelativePath) -Destination $Destination -Force
    }

    $BuildArguments = $GradleArguments + @('--project-dir', $Root, ':app:assembleRelease')
    if (-not $SkipLint) { $BuildArguments += ':app:lintRelease' }
    if ($RefreshDependencies) { $BuildArguments += '--refresh-dependencies' }
    Write-Host '[Build] Building Android APK...'
    Invoke-Gradle $GradleBat $BuildArguments
    $Apk = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'Gradle exited successfully, but the expected APK is missing.' }
    Write-Host "[OK] APK: $Apk"
    Write-Host "[SHA256] $((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant())"
}
finally {
    # Restoration runs on success, validation/download/build failure, and ordinary Ctrl+C.
    # Force-terminating PowerShell/Windows can prevent finally from running.
    try {
        if ($LocalPropertiesChanged) {
            if ($HadLocalProperties) { [IO.File]::WriteAllBytes($LocalProperties, $LocalPropertiesBytes) }
            elseif (Test-Path -LiteralPath $LocalProperties) { Remove-Item -LiteralPath $LocalProperties -Force }
        }
    }
    finally {
        if ($null -ne $Lock) { $Lock.Dispose() }
        foreach ($Name in $SavedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($Name, $SavedEnvironment[$Name], 'Process')
        }
        [Net.ServicePointManager]::SecurityProtocol = $OldTls
        $ProgressPreference = $OldProgress
        Set-Location -LiteralPath $OldLocation.Path
    }
}
