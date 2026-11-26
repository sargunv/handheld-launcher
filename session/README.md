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

The directory is also the `:session` Gradle module, a Kotlin Multiplatform
library for Android and the JVM. Its build runs cargo: the host's library, for
the JVM and for generating the UniFFI Kotlin bindings, and a release build for
each Android ABI with the NDK that `gradle/libs.versions.toml` pins. Nothing
generated is checked in. `-Phandheld.session.abis=x86_64` builds one ABI, for
quicker work on an emulator.

`GameSession` is the app's way in: a session on a thread of its own, which runs
the core at its frame rate between the app's commands. It starts paused. On
Android it presents to an `ANativeWindow` through an OpenGL ES context, or a
Vulkan device for Vulkan cores, that outlives the windows, plays audio through
AAudio, and `GameSessionView` shows it in a `SurfaceView`, feeding it the
gamepads and pausing it while the surface is gone or the window loses focus.
Each gamepad plays on the lowest free controller port as it connects, so a
handheld's own controls are port 0; the session sets a RetroPad on each of the
four ports, or on as many as the core declares. A `SessionListener` hears, on a
thread of its own, when the core changes its frame size, aspect ratio or frame
rate, which the session already follows, and when the core quits, after the
session has stopped. Save memory is written to its file within a few seconds of
changing and whenever the session pauses or stops, on a thread of its own,
replacing the file atomically. A save file whose size differs from the core's
save memory is copied to a backup beside it before it's replaced.

A zipped game goes to the core as is when the core lists `zip` among its
extensions or blocks extraction, as arcade cores do. Otherwise the session
extracts the file in it that the core opens, the largest if several do, into the
content cache directory the app gives it, which holds only the last game
extracted, and loads that, as RetroArch does. Other archive formats go to the
core as they are.

On Android, frames start on the display's vsync, which a Choreographer thread
follows, when the core's rate is within 2% of a whole fraction of the display's:
every second vsync for a 60 fps core on a 120 Hz display. The core then runs
that much faster or slower, and its audio is resampled to match, with rate
control keeping AAudio's queue at about a burst. Other cores, and sessions with
no window, run on a timer at the core's rate. The session logs its frame timing,
audio latency and underruns to logcat each minute and when it pauses.

## Achievements

With `achievements` in its options and an `AchievementsHttp` from the app, a
session takes part in RetroAchievements through
[rcheevos](https://github.com/RetroAchievements/rcheevos)' `rc_client`, which
`vendor/rcheevos/` pins and `build.rs` compiles. It signs in with the player's
token, never their password, hashes the content as RetroAchievements does for
the console the app names (the extracted file, for a zipped game), loads the
game's achievements and checks them against the core's memory after every frame,
laid out from the core's memory map when it gives one. Its requests go to the
app's `AchievementsHttp` on the events thread, and the app hands each response
back with `achievements_response`; the session takes them between frames, and
once a second while paused. Unlocks reach the listener's `achievement_event`,
and `achievements()` lists the game's achievements. In hardcore, while the game
has achievements or may still, the session refuses to load states and to fast
forward. A loaded state starts every achievement's progress over, as its memory
jumped. `mise run session:rcheevos-bindings` regenerates
`src/achievements/ffi.rs` from the headers.

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
on the session. It plays audio, reads the keyboard and gamepads, loads and saves
save memory, and saves and loads a state with F2 and F4. `--present cpu` copies
frames to the window instead of presenting through EGL, and `--help` lists the
rest. The session presents Vulkan frames only to Android windows, so Vulkan
cores run here with `--present cpu`.

It needs the ALSA and libudev development libraries
(`alsa-lib-devel systemd-devel` on Fedora, `libasound2-dev libudev-dev` on
Debian and Ubuntu), so it builds only with the crate's `runner` feature.
