#!/usr/bin/env bash
# Builds the session's test cores for an Android ABI with the pinned NDK, for
# trying the session on a device (session/README.md). They land, with µCity,
# in ~/.cache/handheld/session-fixtures-android/<abi>.
#
# Usage: android-fixtures.sh [arm64-v8a|x86_64]
set -euo pipefail

abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  x86_64) triple=x86_64-linux-android ;;
  *) echo "unsupported ABI $abi" >&2 && exit 1 ;;
esac

here="$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=session/fixture-sources.sh
source "$here/fixture-sources.sh"

ndk_version="$(sed -n 's/^android-ndk = "\(.*\)"$/\1/p' "$here/../gradle/libs.versions.toml")"
min_sdk="$(sed -n 's/^android-minSdk = "\(.*\)"$/\1/p' "$here/../gradle/libs.versions.toml")"
ndk="${ANDROID_HOME:?set ANDROID_HOME}/ndk/$ndk_version"
[[ -d "$ndk" ]] || {
  echo "Android NDK $ndk_version isn't installed; install it with sdkmanager --install \"ndk;$ndk_version\"" >&2
  exit 1
}
case "$(uname -s)" in
  Darwin) host=darwin-x86_64 ;;
  *) host=linux-x86_64 ;;
esac
cc="$ndk/toolchains/llvm/prebuilt/$host/bin/$triple$min_sdk-clang"

dest="${XDG_CACHE_HOME:-$HOME/.cache}/handheld/session-fixtures-android/$abi"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out="$work/out"
mkdir -p "$out"

echo "Building libretro-samples' test cores for $abi"
fetch_samples "$work/samples"
(
  cd "$work/samples/tests/test"
  "$cc" -shared -fPIC -O2 -std=gnu99 libretro-test.c -Wl,--version-script=link.T -lm \
    -o "$out/test_libretro_android.so"
)
(
  cd "$work/samples/video/opengl/libretro_test_gl_shaders"
  "$cc" -shared -fPIC -O2 -std=gnu99 -I. -DHAVE_OPENGLES -DHAVE_OPENGLES2 -w \
    libretro_gl_test.c glsym/rglgen.c glsym/glsym_es2.c -Wl,--version-script=link.T -lGLESv2 \
    -o "$out/testgl_gles_libretro_android.so"
)

echo "Building Gambatte for $abi"
fetch_gambatte "$work/gambatte"
"$ndk/ndk-build" -s -C "$work/gambatte/libgambatte/libretro/jni" APP_ABI="$abi" \
  APP_PLATFORM="android-$min_sdk" -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
cp "$work/gambatte/libgambatte/libretro/libs/$abi/libretro.so" "$out/gambatte_libretro_android.so"

echo "Downloading µCity"
fetch_ucity "$out/ucity.gbc"

mkdir -p "$(dirname "$dest")"
rm -rf "$dest"
mv "$out" "$dest"
echo "Android test cores are ready in $dest"
