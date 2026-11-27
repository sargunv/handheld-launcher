#!/usr/bin/env bash
# Builds the cores and fetches the content the session's tests run. See
# session/README.md.
set -euo pipefail

here="$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=session/fixture-sources.sh
source "$here/fixture-sources.sh"

dest="${HANDHELD_SESSION_FIXTURES:-${XDG_CACHE_HOME:-$HOME/.cache}/handheld/session-fixtures}"
stamp="$(cat "${BASH_SOURCE[0]}" "$here/fixture-sources.sh" "$here/tests/cores/events.c" |
  sha256sum | cut -d' ' -f1)"
if [[ -f "$dest/.stamp" && "$(cat "$dest/.stamp")" == "$stamp" ]]; then
  echo "Session fixtures are up to date in $dest"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jobs="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
case "$(uname -s)" in
  Darwin) ext=dylib gpu=false ;;
  *) ext=so gpu=true ;;
esac

out="$work/out"
mkdir -p "$out"

echo "Building libretro-samples' test cores"
fetch_samples "$work/samples"
# An empty ARCHFLAGS stops the Makefile building for i386 and x86_64 on macOS.
make -s -C "$work/samples/tests/test" -j"$jobs" ARCHFLAGS=
cp "$work/samples/tests/test/test_libretro.$ext" "$out/test_libretro.so"
if [[ "$gpu" == true ]]; then
  for variant in gl gles core; do
    src="$work/samples-$variant"
    cp -r "$work/samples/video/opengl/libretro_test_gl_shaders" "$src"
    case "$variant" in
      gl) flags=() ;;
      gles) flags=(GLES=1) ;;
      core) flags=(CORE=1) ;;
    esac
    make -s -C "$src" -j"$jobs" "${flags[@]}"
    cp "$src/testgl_libretro.so" "$out/testgl_${variant}_libretro.so"
  done
  make -s -C "$work/samples/video/vulkan/vk_rendering" -j"$jobs"
  cp "$work/samples/video/vulkan/vk_rendering/testvulkan_libretro.so" "$out/"
fi

echo "Building the events test core"
cc -shared -fPIC -O2 -std=c99 -I"$here/vendor/libretro" "$here/tests/cores/events.c" \
  -o "$out/events_libretro.so"

echo "Building Gambatte"
fetch_gambatte "$work/gambatte"
make -s -C "$work/gambatte" -j"$jobs" GIT_VERSION=" ${gambatte_commit:0:7}"
cp "$work/gambatte/gambatte_libretro.$ext" "$out/gambatte_libretro.so"

echo "Downloading µCity"
fetch_ucity "$out/ucity.gbc"

echo "$stamp" >"$out/.stamp"
mkdir -p "$(dirname "$dest")"
rm -rf "$dest"
mv "$out" "$dest"
echo "Session fixtures are ready in $dest"
