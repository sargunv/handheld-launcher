# shellcheck shell=bash
# The pinned sources of the session's test fixtures, and the Android API level
# they build for, for fixtures.sh, android-fixtures.sh and CI to source.

samples_commit=7c2ad112d7a5176de2191e473369668da8946b16
samples_sha256=9b23b9579134a86016fd984efaff2408ef83a6fc7eb625589570024ebae5dee3
gambatte_commit=138bcbbf311300a271896bf93a76370c72cab339
gambatte_sha256=01810705ababe48a22fc190039eff5d9785f74ebf7fbd08b0c10d2312c55e99b
ucity_version=v1.3
ucity_sha256=9422ee2ca7b7ea1d46b58b2a429fff3f354dfd3e732dee1e7ae6220f148ce6e0

# The app's minimum Android API level, from Gradle's version catalog.
android_min_sdk() {
  sed -n 's/^android-minSdk = "\(.*\)"$/\1/p' "$(dirname "${BASH_SOURCE[0]}")/../gradle/libs.versions.toml"
}

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

fetch_samples() {
  fetch_source "https://github.com/libretro/libretro-samples/archive/$samples_commit.tar.gz" \
    "$samples_sha256" "$1"
}

fetch_gambatte() {
  fetch_source "https://github.com/libretro/gambatte-libretro/archive/$gambatte_commit.tar.gz" \
    "$gambatte_sha256" "$1"
}

fetch_ucity() {
  fetch "https://github.com/AntonioND/ucity/releases/download/$ucity_version/ucity.gbc" \
    "$ucity_sha256" "$1"
}
