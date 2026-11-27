#!/usr/bin/env bash
# Builds the session's test cores for an Android ABI with the pinned NDK, for
# trying the session on a device (session/README.md). They land, with µCity,
# in $HANDHELD_SESSION_FIXTURES-android/<abi>.
#
# Usage: android-fixtures.sh [arm64-v8a|x86_64]
set -euo pipefail

abi="${1:-arm64-v8a}"

here="$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=session/fixture-sources.sh
source "$here/fixture-sources.sh"

min_sdk="$(android_min_sdk)"
# The NDK's compiler for the ABI, as cargo-ndk finds it for the session's build.
eval "$(cargo ndk-env --target "$abi" --platform "$min_sdk")"
cc=("$CLANG_PATH" "--target=$CARGO_NDK_SYSROOT_TARGET$min_sdk")

dest="$HANDHELD_SESSION_FIXTURES-android/$abi"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out="$work/out"
mkdir -p "$out"

echo "Building libretro-samples' test cores for $abi"
fetch_samples "$work/samples"
(
  cd "$work/samples/tests/test"
  "${cc[@]}" -shared -fPIC -O2 -std=gnu99 libretro-test.c -Wl,--version-script=link.T -lm \
    -o "$out/test_libretro_android.so"
)
(
  cd "$work/samples/video/opengl/libretro_test_gl_shaders"
  "${cc[@]}" -shared -fPIC -O2 -std=gnu99 -I. -DHAVE_OPENGLES -DHAVE_OPENGLES2 -w \
    libretro_gl_test.c glsym/rglgen.c glsym/glsym_es2.c -Wl,--version-script=link.T -lGLESv2 \
    -o "$out/testgl_gles_libretro_android.so"
)
(
  cd "$work/samples/video/vulkan/vk_rendering"
  "${cc[@]}" -shared -fPIC -O2 -std=gnu99 -I. -Iinclude -w libretro-test.c vulkan_symbol_wrapper.c \
    -Wl,--version-script=link.T -lm -o "$out/testvulkan_libretro_android.so"
)

echo "Building the events test core for $abi"
"${cc[@]}" -shared -fPIC -O2 -std=c99 -I"$here/vendor/libretro" "$here/tests/cores/events.c" \
  -o "$out/events_libretro_android.so"

echo "Building Gambatte for $abi"
fetch_gambatte "$work/gambatte"
"$ANDROID_NDK_HOME/ndk-build" -s -C "$work/gambatte/libgambatte/libretro/jni" APP_ABI="$abi" \
  APP_PLATFORM="android-$min_sdk" -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
cp "$work/gambatte/libgambatte/libretro/libs/$abi/libretro.so" "$out/gambatte_libretro_android.so"

echo "Downloading µCity"
fetch_ucity "$out/ucity.gbc"

mkdir -p "$(dirname "$dest")"
rm -rf "$dest"
mv "$out" "$dest"
echo "Android test cores are ready in $dest"
