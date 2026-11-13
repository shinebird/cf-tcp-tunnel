#requires -Version 5.1
# Backward-compatible entry point. The implementation is in build-apk.ps1.
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
& (Join-Path $PSScriptRoot 'build-apk.ps1') @PSBoundParameters
