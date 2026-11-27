# Screens

The screens' structure, taking Steam Deck's layout as the model. It settles
layout and behavior, not the visual design.

## Structure

- **Start opens the main menu** on every launcher screen, sign-in included: a
  side menu over the current screen with **Home**, **Library**, **Downloads**
  (with the number of games in the queue) and **Settings**. Choosing one goes
  there. Home is where the app opens, and B on another top-level page goes back
  to Home. Before sign-in it offers only Settings, with Controls and About.
- **L and R switch the current page's tabs**, wherever a page has them. Inside a
  platform or collection, they step to its neighbor.
- **The scope of a choice comes from where it was made.** Something chosen from
  a game's menu applies to that game, from a platform's menu to the platform,
  and from a collection's menu to the collection. No screen asks which of them a
  choice is for.
- On a two-screen device, the lower screen describes whatever is focused on the
  upper one. Its buttons are for touch. Nothing on it is needed with a
  controller.
- Every screen shows its button hints along the bottom edge of the upper screen.
- Select does nothing in the launcher; in a built-in game it is the game's.

## Sign in

The user enters the server address, then approves a code from another device
through RomM's device sign-in. A pasted API token is the fallback. The app
doesn't discover servers on the local network, because RomM doesn't advertise
itself there.

Beside the address, **Allow uploading games to your server** is off by default.
On, the code also asks RomM for permission to upload games and to scan them into
the library. Signing in again to allow uploads starts with it on.

## Home

Opens with the game you were last playing focused, and A plays it; X opens its
menu, which goes to its Game page. Below it come shelves: recent games,
collections, and recently added to the server. A collection opens its games, as
in the Library. The user can turn each shelf on or off and reorder them. They're
all on by default.

## Library

Four tabs, switched with L and R: **On device**, **Platforms**, **Collections**
and **All games**. The Library opens on the tab it was last left on, Platforms
the first time.

- **On device** and **All games** are grids of games.
- **Platforms** is a grid of platforms, each with its name, its number of games
  and how many of them are on the device. **Collections** is the same for the
  server's collections. The lower screen describes the focused one.

A opens a platform's or collection's games. Covers show whether the game is on
the device and how far its download has got. The lower screen holds filters,
sort and an A–Z jump. L and R move to the previous or next platform or
collection, a shortcut that stops at the ends. B goes back to the tab, with the
one you were in focused. X on a platform or collection opens its menu, which
holds **Open with** for all of its games.

A file in a platform's folder on the device that matches none of the server's
games, by name or content, is a **local game**. It's in its platform's games, On
device and All games, titled by its file name, with a plain tile for a cover.
The tile and the lower screen say it's not on your server.

A disc set copied in loose, a playlist (`.m3u`), cue sheet (`.cue`) or GDI with
the files it lists beside it, is one local game, which opens the playlist or
sheet. Once a scan matches a local game's file to a game of the server, what was
kept for the file goes to that game: its Open with choice, unless the server's
game has its own, and its built-in saves. When the server's game has saves of
its own, those stay, and the local game's go to its save history.

## Game

One primary action, focused first: Play when the game is on the device, Download
with its size when it isn't. Everything else is in the menu on X: Open with,
Remove from device, Cancel download. The lower screen shows the save history
across devices, what the copy on the device says about itself in its header,
such as its serial and region, and where the game opens, with the rule that
decides it.

A local game always has Play. Its menu holds:

- **Open with**, for its file.
- **Remove from device**, which deletes the file after asking.
- **Upload to RomM**, which sends the file to its platform's folder on the
  server after asking, and only then. A sign-in that didn't ask for permission
  to upload says so first, and offers to sign in again with it, to the same
  server, keeping the device's games, saves and settings. An account that may
  not upload is told so instead. Its progress shows on the primary action, and
  Back stops it. RomM's refusal shows in its words, and when the sign-in's
  permissions aren't known, as for a pasted token, it offers the same sign-in
  again. Once uploaded, RomM is asked to scan the platform and the library
  syncs.

Its saves don't sync, since RomM files saves by its own games, and the lower
screen says they stay on the device until the game is on your server. Once a
scan matches the file to a game of the server, the page becomes that game's.

## Open with

From the menu of a game, a platform or a collection, or from Settings →
Emulators for a platform. It lists Built-in, the app's own player, and every
installed app, and A chooses one for what it was opened from. The lower screen
shows the rule chain that decides where the game, or the platform's games, open.

## Downloads

The queue, with each game's progress. A on a game opens its Game page, and X
opens its menu: retry, cancel. The lower screen shows each storage volume's free
space.

## Settings

Sections in a list on the left, the focused section's settings on the right:

- **Controls:** which button confirms, and the A button's position for built-in
  games.
- **RomM:** the server and account, whether uploads are allowed, Sync now, and
  Sign out. When the server refuses the sign-in, it says so here, with Sign in
  again. When the sign-in didn't ask for permission to upload, Allow uploads
  signs in again with it, after asking.
- **RetroAchievements:** the account and hardcore mode.
- **Emulators:** every platform's default app, each opening Open with for that
  platform, and Close emulators where the device allows it.
- **Storage:** each storage volume with its free space, turned on or off, in the
  order new games fill them. Confirm turns one on or off, and its menu moves it
  up or down. New games go to the first that's on and has room; the last one on
  can't be turned off. Finding games looks on every volume, so a game on a
  volume that's off still shows and plays.
- **About:** the version, the licence and source, and the third-party
  components' licences.

## Quick menu

Back, or a controller's guide button, pauses a built-in game and opens the menu
over it: resume, save and load state, rewind, fast forward, controls, screen and
shaders, achievements, quit. Start and every other game button stay the game's.
The lower screen shows the save-state slots. Standalone emulators get a cut-down
version (quit and sync) only if an overlay can do that without trading away
something else.

## About

From Settings. The app's version, its licence and where its source is, and the
third-party components it's built from, each with its version and licence. A
shows a component's full licence text. The lower screen describes the focused
component.

## Save conflicts

Shown before launch, only when both copies changed since the last sync. The
newer copy is focused, choosing takes one press, and the other copy is kept in
the game's save history.
