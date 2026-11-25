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
- `mise run session:fixtures`: build the cores and fetch the content the
  session's tests run. `mise run test` runs it first.
- `mise run session:run -- <core> [content]`: run a core in the session's
  desktop development runner (session/README.md).
- `mise run session:android-fixtures -- <abi>`: build the test cores for
  Android, to run in the debug app's session test screen (session/README.md).

## Project invariants
