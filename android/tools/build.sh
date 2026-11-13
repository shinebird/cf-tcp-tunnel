#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK (platform 37.0 and build-tools 37.0.0)}"
bash tools/prepare-native.sh --check
version=9.3.1
cache="$PWD/.gradle-dist"
gradle="$cache/gradle-$version/bin/gradle"
if [ ! -x "$gradle" ]; then
  mkdir -p "$cache"
  url="https://services.gradle.org/distributions/gradle-$version-bin.zip"
  curl -fL "$url" -o "$cache/gradle.zip"
  expected=$(curl -fsSL "$url.sha256")
  printf '%s  %s\n' "$expected" "$cache/gradle.zip" | sha256sum -c -
  unzip -q -o "$cache/gradle.zip" -d "$cache"
fi
bash tools/prepare-native.sh
"$gradle" --no-daemon wrapper --gradle-version "$version" --distribution-type bin
"$gradle" --no-daemon -PcfNdkPath="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}" -PcfCmakeVersion="${CF_CMAKE_VERSION:-4.1.2}" -PcfAbis="${CF_ABIS:-arm64-v8a}" :app:assembleRelease :app:lintRelease
printf '\nAPK: app/build/outputs/apk/release/app-release.apk\n'
