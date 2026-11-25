# Session

The game session: a libretro frontend in Rust that loads a core and its content,
runs it a frame at a time, and owns its video, audio, input and saves. The app
drives it through UniFFI.

The platform layer plugs in through `VideoSink` or a `NativeWindow` for video,
`AudioSink` for audio, and `InputState` for controllers. Cores that render with
OpenGL or OpenGL ES draw on an EGL context the session owns, either headless,
with frames read back for the sink, or on the window it presents to.

`vendor/libretro/` holds the pinned libretro API header, and `src/ffi/` the
bindings generated from it.

## From Kotlin

The directory is also the `:session` Gradle module, a Kotlin Multiplatform
library for Android and the JVM. Its build runs cargo: the host's library, for
the JVM and for generating the UniFFI Kotlin bindings, and a release build for
each Android ABI with the NDK that `gradle/libs.versions.toml` pins. Nothing
generated is checked in. `-Phandheld.session.abis=x86_64` builds one ABI, for
quicker work on an emulator.

`GameSession` is the app's way in: a session on a thread of its own, which runs
the core at its frame rate between the app's commands. It starts paused. On
Android it presents to an `ANativeWindow` through an OpenGL ES context that
outlives the windows, plays audio through AAudio, and `GameSessionView` shows it
in a `SurfaceView`, feeding it the gamepad and pausing it while the surface is
gone or the window loses focus. Save memory is written when the session pauses
or stops.

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
absolute. F2 saves a state and F4 loads it.

## Tests

The integration tests run real cores: libretro-samples' software and GL test
cores, and Gambatte with µCity, a Game Boy Color game under the GPL-3.0 with CC
BY-SA 4.0 graphics and music. `fixtures.sh`, which `mise run session:fixtures`
runs, builds the cores from pinned commits and downloads the game into
`~/.cache/handheld/session-fixtures`, or `$HANDHELD_SESSION_FIXTURES`. Building
them needs a C and C++ toolchain and the OpenGL and OpenGL ES development
libraries (`mesa-libGL-devel mesa-libGLES-devel` on Fedora,
`libgl-dev libgles-dev` on Debian and Ubuntu).

The GL tests skip on machines without a usable EGL, unless
`HANDHELD_REQUIRE_EGL` is set, as it is in CI. Set `HANDHELD_SESSION_DUMP_DIR`
to a directory to see the frames the tests check, as PNGs.

## Desktop runner

`mise run session:run -- <core> [content]` runs a core in a window, for working
on the session. It plays audio, reads the keyboard and gamepads, loads and saves
save memory, and saves and loads a state with F2 and F4. `--present cpu` copies
frames to the window instead of presenting through EGL, and `--help` lists the
rest.

It needs the ALSA and libudev development libraries
(`alsa-lib-devel systemd-devel` on Fedora, `libasound2-dev libudev-dev` on
Debian and Ubuntu), so it builds only with the crate's `runner` feature.
