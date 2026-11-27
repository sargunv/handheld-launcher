# Handheld

The software a gaming handheld runs: browse, get, play and keep games from a
RomM server. Read [docs/vision.md](docs/vision.md) before you design a feature.
It sets the scope and the priorities. Before you build a screen, read
[docs/screens.md](docs/screens.md) for its agreed structure.

## Project map

- `apps/android/`: the Android app.
- `apps/desktop/`: the desktop app, for development now and Linux handhelds
  later.
- `ui/`: the shared Compose Multiplatform interface: design system, focus and
  navigation, screens.
- `domain/`: the app's model and rules, with no UI or I/O.
- `data/`: everything that talks to RomM, RetroAchievements or the device's
  storage.
- `session/`: the Rust game session that hosts libretro cores, used from Kotlin
  through UniFFI.
- `rom-headers/`: a Rust crate that reads each platform's own ROM and disc
  headers, which the session uses. It depends on nothing in the app, so it can
  be published on its own.
- `docs/`: the vision and other high-level docs.

## Dev tool commands

`mise install` installs the pinned tools, `mise tasks` lists the tasks, and
`mise run <task> --help` shows a task's arguments. What the list doesn't say:

- `mise run run:android` and `mise run android:open` take
  `HANDHELD_LAYOUT=frame` (or `folded`) to force a dual-screen layout.
- `mise run android:open <target>` shows a screen straight away;
  `mise run android:open --help` lists the targets. Before sign-in, only the
  `settings` targets show. It's the launch intent's `open` extra, so
  `adb shell am start -n dev.sargunv.handheld/.MainActivity --es open game:42`
  does the same.
- Before confirming on a game in `mise run android:ui`'s output, check it's on
  the device, or confirming downloads it: Home's covers say so in their label,
  and Library marks the games on the device with an `onDevice:<romId>` element,
  and local games with `onDevice:local:<path>`.
- session/README.md covers the session's fixtures, its desktop runner and the
  debug app's session test activity.

## Android dev device

The default dev device is Innkeeper, an x86_64 Android userspace on the Linux
desktop that exposes adb. The Android tasks and Gradle's install use
`$ANDROID_SERIAL`; set it to your Innkeeper profile's serial, since other
devices may be connected. Innkeeper has one display, so use
`HANDHELD_LAYOUT=frame` to see both screens. Don't set `overlay_display_devices`
to simulate a second display: it crashes Innkeeper's system_server.

Agents and people often share one device. Hold its lock,
`/tmp/handheld-device-$ANDROID_SERIAL.lock`, while you install or drive it, and
keep holds short. `mise run run:android` takes it around installing and
launching, and waits while someone else holds it. For your own device work, run
it under `mise run android:lock -- <command>`, which releases the lock when the
command ends, even when it fails. The lock is a directory: a holder that was
killed outright leaves it behind, so remove it once nobody is using the device.

## Licences

About lists what each app ships with its licence texts, collected when it
builds. AboutLibraries' Gradle plugin lists the Kotlin dependencies, offline,
and takes their licence texts from `licences/licenses/`: a dependency under a
licence with no text there fails the build, so add its SPDX text. The Android
app adds the Rust crates the session links, which cargo-about finds with
`session/about.toml`, and the vendored C code, whose entries are checked in to
`session/licences/`, with rcheevos' text read from its LICENSE.
`licences/libraries/` holds the code ported from elsewhere.

## Secrets

Development secrets, such as a read-only token for a RomM server, are encrypted
in `fnox.toml`. Read one with `fnox get NAME`, or run a command with all of them
in its environment with `fnox exec -- <command>`. The debug builds share one
signing key from fnox, which `mise run run:android` unpacks, so any machine's
build installs over another's. CI has no key, so tests that need a secret skip
there. `mise run test:live` runs the live RomM test.

## Project invariants

- Anything kept per game is keyed by `GameKey`, and goes to a game's ROM in the
  one adopt step when its file stops being a local game. A per-game setting
  lives in a map keyed by `GameKey`, which `Settings.adopted` hands over
  explicitly. Other state implements `PerGameState` and joins the platform's
  `gameState`.
