#!/usr/bin/env bash
# Builds the cores and fetches the content the session's tests run. See
# session/README.md.
set -euo pipefail

samples_commit=7c2ad112d7a5176de2191e473369668da8946b16
samples_sha256=9b23b9579134a86016fd984efaff2408ef83a6fc7eb625589570024ebae5dee3
gambatte_commit=138bcbbf311300a271896bf93a76370c72cab339
gambatte_sha256=01810705ababe48a22fc190039eff5d9785f74ebf7fbd08b0c10d2312c55e99b
ucity_version=v1.3
ucity_sha256=9422ee2ca7b7ea1d46b58b2a429fff3f354dfd3e732dee1e7ae6220f148ce6e0

here="$(dirname "${BASH_SOURCE[0]}")"
dest="$HANDHELD_SESSION_FIXTURES"
stamp="$(cat "${BASH_SOURCE[0]}" "$here/tests/cores/events.c" | sha256sum | cut -d' ' -f1)"
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

# fetch URL SHA256 OUTPUT
fetch() {
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum --check --quiet -
}

# fetch_source URL SHA256 DIR: downloads a tarball and unpacks it into DIR.
fetch_source() {
  fetch "$1" "$2" "$3.tar.gz"
  mkdir "$3"
  tar -xzf "$3.tar.gz" -C "$3" --strip-components=1
}

echo "Building libretro-samples' test cores"
fetch_source "https://github.com/libretro/libretro-samples/archive/$samples_commit.tar.gz" \
  "$samples_sha256" "$work/samples"
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
fetch_source "https://github.com/libretro/gambatte-libretro/archive/$gambatte_commit.tar.gz" \
  "$gambatte_sha256" "$work/gambatte"
make -s -C "$work/gambatte" -j"$jobs" GIT_VERSION=" ${gambatte_commit:0:7}"
cp "$work/gambatte/gambatte_libretro.$ext" "$out/gambatte_libretro.so"

echo "Downloading µCity"
fetch "https://github.com/AntonioND/ucity/releases/download/$ucity_version/ucity.gbc" \
  "$ucity_sha256" "$out/ucity.gbc"

echo "$stamp" >"$out/.stamp"
mkdir -p "$(dirname "$dest")"
rm -rf "$dest"
mv "$out" "$dest"
echo "Session fixtures are ready in $dest"
