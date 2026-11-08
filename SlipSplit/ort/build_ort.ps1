# Builds the reduced ONNX Runtime AAR from ops.config and copies it into app/libs.
# Needs: ONNX Runtime source at the tag below, Android SDK with NDK 28.0.13004108, JDK 17,
# and a Python with cmake and ninja (pip install cmake ninja). Keep -Work short: Windows paths overflow.
param(
    [string]$Source = "C:\ort\src",
    [string]$Work = "C:\ort\build",
    # Release (-O2) as in the Maven package; MinSizeRel (-Os) was about 50% slower on the phone.
    [string]$Config = "Release",
    [string]$Python = "python",
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108"
)

$ErrorActionPreference = "Stop"
$version = "1.31.1"
$tag = git -C $Source describe --tags --exact-match
if ($tag -ne "v$version") { throw "$Source is at '$tag', expected v$version." }
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to a JDK 17." }

& $Python "$Source\tools\ci_build\github\android\build_aar_package.py" `
    --build_dir $Work --config $Config `
    --android_sdk_path $Sdk --android_ndk_path $Ndk `
    --include_ops_by_config "$PSScriptRoot\ops.config" `
    "$PSScriptRoot\build_settings.json"
if ($LASTEXITCODE -ne 0) { throw "ONNX Runtime build failed." }

$aar = Get-ChildItem "$Work\aar_out" -Recurse -Filter "onnxruntime-android-$version.aar" | Select-Object -First 1
if (-not $aar) { throw "No AAR found under $Work\aar_out." }
$libs = New-Item -ItemType Directory -Force "$PSScriptRoot\..\app\libs"
Copy-Item $aar.FullName "$libs\onnxruntime-android-minimal-$version.aar" -Force
Write-Host "Copied $($aar.Length) bytes to app\libs\onnxruntime-android-minimal-$version.aar"
