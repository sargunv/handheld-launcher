#!/usr/bin/env bash
# Builds the libretro cores and downloads the content that the session's tests
# run, into a cache directory, from pinned sources with pinned checksums.
#
# The libretro buildbot rebuilds its cores nightly and keeps no old builds, so
# the cores are built from pinned commits instead. Building needs a C and C++
# toolchain and the OpenGL and OpenGL ES development libraries.
#
# The fixtures:
# - test_libretro.so: libretro-samples' software test core (MIT).
# - testgl_{gl,gles,core}_libretro.so: libretro-samples' GL test core, built for
#   OpenGL 2 (compatibility), OpenGL ES 2 and OpenGL 3.1 core (MIT).
# - gambatte_libretro.so: the Gambatte Game Boy core (GPL-2.0).
# - ucity.gbc: µCity, a Game Boy Color city builder by Antonio Niño Díaz (code
#   GPL-3.0-or-later, graphics and music CC BY-SA 4.0).
set -euo pipefail

samples_commit=7c2ad112d7a5176de2191e473369668da8946b16
samples_sha256=9b23b9579134a86016fd984efaff2408ef83a6fc7eb625589570024ebae5dee3
gambatte_commit=138bcbbf311300a271896bf93a76370c72cab339
gambatte_sha256=01810705ababe48a22fc190039eff5d9785f74ebf7fbd08b0c10d2312c55e99b
ucity_version=v1.3
ucity_sha256=9422ee2ca7b7ea1d46b58b2a429fff3f354dfd3e732dee1e7ae6220f148ce6e0

dest="${HANDHELD_SESSION_FIXTURES:-${XDG_CACHE_HOME:-$HOME/.cache}/handheld/session-fixtures}"
stamp="$(sha256sum "${BASH_SOURCE[0]}" | cut -d' ' -f1)"
if [[ -f "$dest/.stamp" && "$(cat "$dest/.stamp")" == "$stamp" ]]; then
  echo "Session fixtures are up to date in $dest"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jobs="$(nproc 2>/dev/null || echo 4)"

# fetch URL SHA256 OUTPUT
fetch() {
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum --check --quiet -
}

out="$work/out"
mkdir -p "$out"

echo "Building libretro-samples' test cores"
fetch "https://github.com/libretro/libretro-samples/archive/$samples_commit.tar.gz" \
  "$samples_sha256" "$work/samples.tar.gz"
mkdir "$work/samples"
tar -xzf "$work/samples.tar.gz" -C "$work/samples" --strip-components=1
make -s -C "$work/samples/tests/test" -j"$jobs"
cp "$work/samples/tests/test/test_libretro.so" "$out/"
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

echo "Building Gambatte"
fetch "https://github.com/libretro/gambatte-libretro/archive/$gambatte_commit.tar.gz" \
  "$gambatte_sha256" "$work/gambatte.tar.gz"
mkdir "$work/gambatte"
tar -xzf "$work/gambatte.tar.gz" -C "$work/gambatte" --strip-components=1
make -s -C "$work/gambatte" -j"$jobs" GIT_VERSION=" ${gambatte_commit:0:7}"
cp "$work/gambatte/gambatte_libretro.so" "$out/"

echo "Downloading µCity"
fetch "https://github.com/AntonioND/ucity/releases/download/$ucity_version/ucity.gbc" \
  "$ucity_sha256" "$out/ucity.gbc"

echo "$stamp" >"$out/.stamp"
mkdir -p "$(dirname "$dest")"
rm -rf "$dest"
mv "$out" "$dest"
echo "Session fixtures are ready in $dest"
