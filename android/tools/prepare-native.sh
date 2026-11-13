#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${CF_BOOST_SOURCE:?Set CF_BOOST_SOURCE to your local Boost Git root}"
: "${CF_OPENSSL_SOURCE:?Set CF_OPENSSL_SOURCE to your local OpenSSL Git root}"
args=(--boost "$CF_BOOST_SOURCE" --openssl "$CF_OPENSSL_SOURCE"
 --ndk "${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
 --abis "${CF_ABIS:-arm64-v8a}" --jobs "${CF_NATIVE_JOBS:-4}")
[[ -z "${CF_NATIVE_WORK_ROOT:-}" ]] || args+=(--work-root "$CF_NATIVE_WORK_ROOT")
"${CF_PYTHON:-python3}" "$ROOT/tools/native_build.py" "${args[@]}" "$@"
