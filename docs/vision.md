# Vision

Handheld (a working name) is the software a gaming handheld runs. It's where you
browse your games, get them onto the device, play them, and keep your saves. A
self-hosted [RomM](https://romm.app) server holds the library. Think of a modern
ES-DE with the feel of Steam Big Picture's console mode, built for Android first
and Linux handhelds later.

## Why it exists

[Argosy](https://github.com/rommapp/argosy-launcher) showed that people want a
RomM client on their handheld, and it collected a lot of hard-won knowledge
about how Android emulators launch and where they keep saves. It also shows what
happens without an editor: every screen navigates differently, media players and
social features crowd out the basics, and simple needs go unmet, like sending a
few games of a platform to a different app. It also looks like every other
generated UI.

Handheld starts over with the same goal and a narrower focus. It's GPL-3, so it
can carry over what Argosy learned.

## Who it's for

Someone who runs a RomM server, owns a dedicated handheld (the AYN Thor first),
and plays with the controls in their hands. They own their library and want the
device to feel like a console, not like a phone running a launcher.

## What it does

Everything serves one of four jobs.

- **Browse.** The whole library, instantly and offline. Shelves are platforms
  and collections. Search sits on one button.
- **Get.** Downloads that know what fits where, handle multi-disc games, set up
  BIOS and firmware, and accept games sent from RomM's web page.
- **Play.** Systems that libretro handles well run in our own session, where we
  control the screen, the controls, the saves and the quick menu. Systems that
  need a standalone emulator launch in that app, and we wrap the session around
  it. Android games and streaming apps sit in the same library.
- **Keep.** Saves follow you between devices through RomM. Play time is
  reported, and history is kept.

## What it doesn't do

- Play music, audiobooks or video. Other apps do that well.
- Social features, feeds or recaps.
- A theme engine. It has one designed look, done well.
- A feature because one person asked for it. A feature has to serve browse, get,
  play or keep, and has to work with a controller.

## Principles

In priority order, for when they conflict:

1. **Never lose a save.** Sync is explicit about conflicts, keeps history, and
   says what happened in plain words.
2. **Controller first.** Every screen uses the same controls: confirm, back, the
   menu for the focused item, search. Focus is always visible, and comes back
   where you left it.
3. **The server is the truth; the device is a cache.** Everything you've already
   seen works offline.
4. **Every rule has an override.** A game can override its collection, which can
   override its platform, and any installed app can be the emulator at any
   level.
5. **Fast.** It opens instantly, and the home screen never shows a spinner.
6. **Settings live where they apply.** The menu for a game holds that game's
   options. There's no settings tree with fifty sections.
7. **Two screens are a feature, one screen is fine.** The Thor's second screen
   gives context and touch shortcuts. On one screen, the same content becomes a
   sheet.

## How it looks

The library should feel like a well-kept physical collection. Box art is the
only strong color. Around it, the interface is quiet: typography, warm neutral
tones, and generous space. Each platform's shelf takes a faint tone from that
console's hardware, so you know where you are without reading a label. Focus
feels physical: the item lifts, with no glowing outline. Motion is spent in one
place, the moment a game starts. Everything else is instant.

Avoid the defaults: neon accents on black, frosted glass, Material components,
cards with identical shadows, and screens that style the same control in
different ways.

## Platforms

Android handhelds first, starting with the Thor. Linux handhelds such as the
Steam Deck come later, so shared code takes no Android-only shortcuts. The
interface uses Compose Multiplatform. The game session is Rust, so it can run on
both.

## Order of work

1. Replace Argosy for daily use: library, downloads, launching standalone
   emulators, and save sync. The first acceptance test: the Jak and Daxter games
   open in PocketGOAL while every other PS2 game opens in ARMSX2.
2. The built-in libretro session.
3. The quick menu during play, both screens during play, and achievements.
4. Android games and streaming apps in the library.
5. Linux handhelds.
