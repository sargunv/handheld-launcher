# Session

The game session: a libretro frontend in Rust that hosts cores for the app,
which drives it through UniFFI.

## Build

The directory is also the `:session` Gradle module, an Android library. It
compiles the crate for the host to generate UniFFI's Kotlin bindings, and for
each Android ABI listed in `buildSrc` with cargo-ndk, using the NDK that
`mise.toml` pins. Install that NDK with `sdkmanager --install "ndk;<version>"`.
`-Phandheld.session.abis=x86_64` builds one ABI, for quicker work on an
emulator.

## Test

The integration tests run real cores: libretro-samples' software, GL and Vulkan
test cores, `tests/cores/events.c`, and Gambatte with µCity, a Game Boy Color
game. `mise run session:fixtures` builds the cores from pinned commits and
downloads the game into `~/.cache/handheld/session-fixtures`, or
`$HANDHELD_SESSION_FIXTURES`. `mise run test` runs it first; the tests
themselves stay offline. Building the cores needs a C and C++ toolchain and the
OpenGL and OpenGL ES development libraries
(`mesa-libGL-devel mesa-libGLES-devel` on Fedora, `libgl-dev libgles-dev` on
Debian and Ubuntu). On macOS, the Xcode command line tools are enough: the GL
and Vulkan test cores aren't built there.

The GL tests skip without a usable EGL, unless `HANDHELD_REQUIRE_EGL` is set,
and the Vulkan test skips without a usable Vulkan, unless
`HANDHELD_REQUIRE_VULKAN` is set. CI sets both, and runs Vulkan on Mesa's
lavapipe (`mesa-vulkan-drivers` on Debian and Ubuntu). Set
`HANDHELD_SESSION_DUMP_DIR` to a directory to see the frames the tests check, as
PNGs.

## Run on a device

Debug builds of the Android app have `SessionTestActivity`, which runs a core:

```sh
mise run session:android-fixtures -- x86_64   # or arm64-v8a
adb push ~/.cache/handheld/session-fixtures-android/x86_64/. /data/local/tmp/session-test/
adb shell run-as dev.sargunv.handheld sh -c \
  'mkdir -p files/session-test && cp /data/local/tmp/session-test/* files/session-test/'
adb shell am start -n dev.sargunv.handheld/.SessionTestActivity \
  --es core gambatte_libretro_android.so --es content ucity.gbc
```

The core and content paths are relative to the app's `files/session-test`, or
absolute. Innkeeper has no `run-as`, so there, pass the absolute paths under
`/data/local/tmp/session-test` instead of copying the files. `--es platform gbc`
in place of `--es core` installs and runs that platform's core.
`--esa core_options key=value,...` sets core options, such as
`ppsspp_backend=vulkan` for PPSSPP to render with Vulkan. F2 saves a state and
F4 loads it.

## Run on the desktop

`mise run session:run -- <core> [content]` runs a core in a window; `--help`
lists its options. Vulkan cores need `--present cpu`, since the session presents
Vulkan frames only to Android windows. It builds with the crate's `runner`
feature, which needs the ALSA and libudev development libraries
(`alsa-lib-devel systemd-devel` on Fedora, `libasound2-dev libudev-dev` on
Debian and Ubuntu).
