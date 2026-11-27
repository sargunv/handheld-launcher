# Session

The game session: a libretro frontend in Rust that loads a core and its content,
runs it a frame at a time, and owns its video, audio, input and saves. The app
drives it through UniFFI.

The platform layer plugs in through `VideoSink` or a `NativeWindow` for video,
`AudioSink` for audio, and `InputState` for controllers. Cores that render with
OpenGL or OpenGL ES draw on an EGL context the session owns, either headless,
with frames read back for the sink, or on the window it presents to. Cores that
render with Vulkan get an instance and device the session makes, or that they
make through libretro's context negotiation, and hand over images that the
session reads back for the sink or copies to an Android window's swapchain. The
session loads EGL and the Vulkan loader at run time, so it runs where either is
missing.

`vendor/libretro/` holds the pinned libretro API header, and `src/ffi/` the
bindings generated from it.

## From Kotlin

The directory is also the `:session` Gradle module, an Android library. Its
build compiles the crate for the host only to generate UniFFI's Kotlin bindings,
which aren't checked in. It compiles the crate for each Android ABI listed in
`buildSrc` with cargo-ndk, using the NDK that `mise.toml` pins. Install that NDK
with `sdkmanager`. `-Phandheld.session.abis=x86_64` builds one ABI, for quicker
work on an emulator.

## Achievements

The session takes part in RetroAchievements through
[rcheevos](https://github.com/RetroAchievements/rcheevos), which
`vendor/rcheevos/` pins and `build.rs` compiles; the `achievements` module's
docs say how.

## On a device

Debug builds of the Android app have a screen that runs a core:

```sh
mise run session:android-fixtures -- x86_64   # or arm64-v8a
adb push ~/.cache/handheld/session-fixtures-android/x86_64/. /data/local/tmp/session-test/
adb shell run-as dev.sargunv.handheld sh -c \
  'mkdir -p files/session-test && cp /data/local/tmp/session-test/* files/session-test/'
adb shell am start -n dev.sargunv.handheld/.MainActivity --es debug_screen session_test \
  --es core gambatte_libretro_android.so --es content ucity.gbc
```

The core and content paths are relative to the app's `files/session-test`, or
absolute. Innkeeper has no `run-as`, so there, pass the absolute paths under
`/data/local/tmp/session-test` instead of copying the files. F2 saves a state
and F4 loads it. `--esa core_options key=value,...` sets core options, such as
`ppsspp_backend=vulkan` for PPSSPP to render with Vulkan.

## Tests

The integration tests run real cores: libretro-samples' software, GL and Vulkan
test cores, `tests/cores/events.c`, which quits, switches video modes and shows
which ports are pressed on request, and Gambatte with µCity, a Game Boy Color
game under the GPL-3.0-or-later with CC BY-SA 4.0 graphics and music.
`fixtures.sh`, which `mise run session:fixtures` runs, builds the cores from
pinned commits and downloads the game into `~/.cache/handheld/session-fixtures`,
or `$HANDHELD_SESSION_FIXTURES`. `mise run test` runs it first, so the first run
on a cold cache builds and downloads them; the tests themselves stay offline. CI
caches them by the files that pin them. Building them needs a C and C++
toolchain and the OpenGL and OpenGL ES development libraries
(`mesa-libGL-devel mesa-libGLES-devel` on Fedora, `libgl-dev libgles-dev` on
Debian and Ubuntu). On macOS, the Xcode command line tools are enough: the GL
and Vulkan test cores aren't built there.

The GL tests skip on machines without a usable EGL, unless
`HANDHELD_REQUIRE_EGL` is set, and the Vulkan test skips without a usable
Vulkan, unless `HANDHELD_REQUIRE_VULKAN` is set. CI sets both, and runs Vulkan
on Mesa's lavapipe (`mesa-vulkan-drivers` on Debian and Ubuntu). macOS has
neither, so they're ignored there. Set `HANDHELD_SESSION_DUMP_DIR` to a
directory to see the frames the tests check, as PNGs.

## Desktop runner

`mise run session:run -- <core> [content]` runs a core in a window, for working
on the session; `--help` lists its options. Vulkan cores need `--present cpu`,
since the session presents Vulkan frames only to Android windows. It builds with
the crate's `runner` feature, which needs the ALSA and libudev development
libraries (`alsa-lib-devel systemd-devel` on Fedora,
`libasound2-dev libudev-dev` on Debian and Ubuntu).
