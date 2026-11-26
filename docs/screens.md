# Screens

The screens' structure, as agreed in the first round of wireframes
([wireframes.html](wireframes.html)). It settles layout and behavior, not the
visual design.

## Structure

- Three top-level places, switched with L and R: **Home**, **Library** and
  **Downloads**. Global settings live in the quick menu on Start.
- On a two-screen device, the lower screen describes whatever is focused on the
  upper one. Its buttons are for touch. Nothing on it is needed with a
  controller.
- Every screen shows its button hints along the bottom edge of the upper screen.

## Sign in

The user enters the server address, then approves a code from another device
through RomM's device sign-in. A pasted API token is the fallback. The app
doesn't discover servers on the local network, because RomM doesn't advertise
itself there.

## Home

Opens with the game you were last playing focused, so A resumes it. Below it
come shelves: recent games, collections, and recently added to the server. The
user can turn each shelf on or off and reorder them. They're all on by default.

## Library

Opens on a grid of platforms, each with its name, its number of games and how
many of them are on the device. An optional "All games" entry comes first and
shows the whole library in one grid. It's off by default, for small libraries
that want it. The lower screen describes the focused platform.

A opens a platform's games. Covers show whether the game is on the device and
how far its download has got. The lower screen holds filters, sort and an A–Z
jump. L and R move to the previous or next platform, a shortcut that stops at
the ends. B goes back to the platforms, with the one you were in focused. On the
grid of platforms, L and R switch places as everywhere else.

## Game

One primary action, focused first: Play when the game is on the device, Download
with its size when it isn't. Everything else is in the menu on X. The lower
screen shows the save history across devices.

## Open with

From a game's menu. Any installed app can be chosen, and the choice applies to
the game, its collection or its platform. The lower screen shows the rule chain
that decides where the game opens.

## Quick menu

Back, or a controller's guide button, pauses a built-in game and opens the menu
over it: resume, save and load state, rewind, fast forward, controls, screen and
shaders, achievements, quit. Start and every other game button stay the game's.
The lower screen shows the save-state slots. Standalone emulators get a cut-down
version (quit and sync) only if an overlay can do that without trading away
something else.

## About

From the quick menu. The app's version, its licence and where its source is, and
the third-party components it's built from, each with its version and licence. A
shows a component's full licence text. The lower screen describes the focused
component.

## Save conflicts

Shown before launch, only when both copies changed since the last sync. The
newer copy is focused, choosing takes one press, and the other copy is kept in
the game's save history.
