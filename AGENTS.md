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
- `domain/`: the library model, launch rules and sync logic, with no UI or I/O.
- `data/`: the RomM client, the local cache and downloads.
- `session/`: the Rust game session that hosts libretro cores, used from Kotlin
  through UniFFI.
- `docs/`: the vision and other high-level docs.

## Dev tool commands

`mise install` installs the pinned tools. `mise tasks` lists every task. The
common ones:

- `mise run build`: build the apps and the session crate.
- `mise run test`: the Kotlin and Rust tests.
- `mise run check`, `mise run fix`: the formatters and linters (hk.pkl).
- `mise run run:desktop`: run the desktop app.
- `mise run run:android`: install and launch on the connected device.
  `HANDHELD_LAYOUT=frame` (or `folded`) forces a dual-screen layout.
- `mise run android:screenshot`: save the device's screen to a temp file and
  print its path.
- `mise run android:action <action>`: send a semantic action (`confirm`, `back`,
  `up`, `menu`, ...) to the app as a key event, on display 0.
- `mise run android:ui`: print where the app is, on every display, without a
  screenshot: the focused element first, then each visible element with its test
  tag, text, accessibility label and bounds. Confirming on a game that isn't on
  the device downloads it, so check first: Home's covers say so in their label,
  and Library marks the games on the device with an `onDevice:<romId>` element.
- `mise run android:open <target>`: show a screen of the debug app straight
  away: `place:home`, `place:library`, `place:downloads`, `place:settings`,
  `game:<romId>`, `library`, `library:<platform-slug>`, `settings` or
  `settings:<section>` (`controls`, `romm`, `retroachievements`, `emulators`,
  `storage`, `about`). Before sign-in, only the `settings` targets show. It's
  the `open` extra of the launch intent, so
  `adb shell am start -n dev.sargunv.handheld/.MainActivity --es open game:42`
  does the same.
- `mise run android:lock -- <command>`: run a command while holding the device's
  lock (below).
- `mise run session:fixtures`: build the cores and fetch the content the
  session's tests run. `mise run test` runs it first.
- `mise run session:run -- <core> [content]`: run a core in the session's
  desktop development runner (session/README.md).
- `mise run session:android-fixtures -- <abi>`: build the test cores for
  Android, to run in the debug app's session test screen (session/README.md).

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
command ends, even when it fails. It also takes the older
`/tmp/handheld-thor.lock` that earlier agents used. The lock is a directory: a
holder that was killed outright leaves it behind, so remove it once nobody is
using the device.

## Licences

About lists what each app ships with its licence texts, collected when it
builds. AboutLibraries' Gradle plugin lists the Kotlin dependencies, offline,
and takes their licence texts from `licences/licenses/`: a dependency under a
licence with no text there fails the build, so add its SPDX text. The Android
app adds the Rust crates the session links, which cargo-about finds with
`session/about.toml`, and the vendored C code in `session/licences/`, with
licence texts from the vendored files. `licences/libraries/` holds the code
ported from elsewhere.

## Secrets

Development secrets, such as a read-only token for a RomM server, are encrypted
in `fnox.toml`. Read one with `fnox get NAME`, or run a command with all of them
in its environment with `fnox exec -- <command>`. The debug builds share one
signing key from fnox, which `mise run run:android` unpacks, so any machine's
build installs over another's. CI has no key, so tests that need a secret skip
there. `mise run test:live` runs the live RomM test.

## Project invariants
