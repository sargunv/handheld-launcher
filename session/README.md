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
