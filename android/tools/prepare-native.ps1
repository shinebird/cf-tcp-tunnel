#requires -Version 5.1
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$AndroidSdkPath,
 [string]$NdkPath='', [string]$NdkVersion='30.0.16248370',
 [string]$CmakeVersion='4.1.2', [string]$NativeAbis='arm64-v8a',
 [string]$BoostSource='', [string]$OpenSSLSource='', [string]$PythonPath='python',
 [string]$MsysRoot='', [string]$NativeWorkRoot='',
 [ValidateRange(1,128)][int]$NativeJobs=4, [string]$ProxyUrl='', [switch]$CheckOnly
)
Set-StrictMode -Version 2.0
$ErrorActionPreference='Stop'
if(-not $BoostSource -or -not $OpenSSLSource){throw 'Supply both -BoostSource and -OpenSSLSource. This version has no dependency-download backend.'}
$Ndk=if($NdkPath){(Get-Item -LiteralPath $NdkPath).FullName}else{Join-Path $AndroidSdkPath "ndk\$NdkVersion"}
foreach($p in @((Join-Path $Ndk 'build\cmake\android.toolchain.cmake'),
 (Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'),
 (Join-Path $AndroidSdkPath "cmake\$CmakeVersion\bin\cmake.exe"))){
 if(-not (Test-Path -LiteralPath $p -PathType Leaf)){throw "Missing native tool: $p. Select installed NDK/CMake with -NdkVersion/-CmakeVersion or -NdkPath."}
}
if(-not $MsysRoot){
 foreach($Candidate in @('D:\Software\Tools\msys64','C:\msys64','D:\msys64')){
  if(Test-Path -LiteralPath (Join-Path $Candidate 'usr\bin\bash.exe')){$MsysRoot=$Candidate;break}
 }
}
if(-not $MsysRoot){throw 'Specify -MsysRoot for an existing MSYS2 installation with MSYS perl and make (pacman -S --needed perl make). No tools will be downloaded automatically.'}
foreach($Name in @('bash','perl','make','cygpath')){
 $Tool=Join-Path $MsysRoot "usr\bin\$Name.exe"
 if(-not(Test-Path -LiteralPath $Tool -PathType Leaf)){throw "Missing $Tool. Install MSYS2 packages perl and make; MinGW/Strawberry Perl is not supported."}
}
$Python=(Get-Command $PythonPath -ErrorAction Stop).Source
$ArgsList=@((Join-Path $PSScriptRoot 'native_build.py'),'--boost',$BoostSource,'--openssl',$OpenSSLSource,
 '--ndk',$Ndk,'--bash',(Join-Path $MsysRoot 'usr\bin\bash.exe'),'--abis',$NativeAbis,'--jobs',"$NativeJobs")
if($NativeWorkRoot){$ArgsList+=@('--work-root',$NativeWorkRoot)}
if($CheckOnly){$ArgsList+='--check'}
& $Python @ArgsList
if($LASTEXITCODE -ne 0){throw "Local native dependency build failed (exit $LASTEXITCODE)."}
