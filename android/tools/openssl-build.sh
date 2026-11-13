#!/usr/bin/env bash
# Invoked by native_build.py. No package manager or network operations.
set -euo pipefail
export PATH="/usr/bin:/bin:$PATH"
unset CC CXX CFLAGS CXXFLAGS CPPFLAGS LDFLAGS CROSS_COMPILE CROSS_SYSROOT
perl_cmd=perl
make_cmd=make
case "$(uname -s)" in
 MSYS*|MINGW*|CYGWIN*)
   # Recent MSYS2 Perl identifies as cygwin; older packages report msys.
   # Pin both tools to the selected MSYS2 installation, not a Windows PATH entry.
   perl_cmd=/usr/bin/perl
   make_cmd=/usr/bin/make
   [[ -x "$perl_cmd" && -x "$make_cmd" ]] || { echo 'Missing MSYS2 /usr/bin/perl or /usr/bin/make.' >&2; exit 1; }
   perl_os=$("$perl_cmd" -e 'print $^O') || { echo "Cannot run $perl_cmd" >&2; exit 1; }
   printf '[Native] Perl=%s; OS=%s; make=%s\n' "$perl_cmd" "$perl_os" "$make_cmd"
   case "$perl_os" in
     msys|cygwin) ;;
     *) echo "Unsupported Perl OS '$perl_os' from $perl_cmd; expected msys or cygwin (not native Windows Perl)." >&2; exit 1;;
   esac
   host=windows-x86_64;;
 Linux*) host=linux-x86_64;;
 Darwin*) host=darwin-x86_64;;
 *) echo 'Unsupported host' >&2; exit 1;;
esac
command -v "$make_cmd" >/dev/null
command -v "$perl_cmd" >/dev/null
if [[ "${1:-}" == --check ]]; then
 "$perl_cmd" -v | sed -n '1,3p'
 "$make_cmd" --version | sed -n '1p'
 exit 0
fi
norm() { if command -v cygpath >/dev/null; then cygpath -u "$1"; else printf '%s\n' "$1"; fi; }
src=$(norm "$1"); ndk=$(norm "$2"); prefix=$(norm "$3"); abi=$4; jobs=$5
export ANDROID_NDK_ROOT="$ndk"
unset ANDROID_NDK
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
[[ -d "$bin" ]] || { echo "Missing NDK host tools: $bin" >&2; exit 1; }
case "$abi" in
 arm64-v8a) target=android-arm64; triple=aarch64-linux-android;;
 x86_64) target=android-x86_64; triple=x86_64-linux-android;;
 *) exit 2;;
esac
cd "$src"
# Windows NDK ships .cmd wrappers. Provide a POSIX executable wrapper in our
# private build tree; never edit the SDK. Configure still detects NDK clang.
mkdir -p cf-tool-wrappers
if [[ "$host" == windows-x86_64 ]]; then
 printf '#!/usr/bin/env bash\nexec "%s/clang.exe" --target=%s26 "$@"\n' "$bin" "$triple" > "cf-tool-wrappers/${triple}26-clang"
 chmod +x "cf-tool-wrappers/${triple}26-clang"
fi
export PATH="$PWD/cf-tool-wrappers:$bin:$PATH"
"$perl_cmd" ./Configure "$target" -D__ANDROID_API__=26 no-shared no-tests no-module no-dso no-engine \
 -fPIC --prefix="$prefix" --openssldir="$prefix/ssl" --libdir=lib
"$make_cmd" -j"$jobs" build_libs
# install_dev installs headers and the two archives, not apps/tests/providers.
"$make_cmd" install_dev
[[ -s "$prefix/lib/libssl.a" && -s "$prefix/lib/libcrypto.a" && -f "$prefix/include/openssl/ssl.h" ]]
