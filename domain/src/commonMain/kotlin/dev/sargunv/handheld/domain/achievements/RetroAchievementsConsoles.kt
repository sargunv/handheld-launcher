package dev.sargunv.handheld.domain.achievements

import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * RetroAchievements' id for the console a platform's games run on, which tells rcheevos how to hash
 * a game and lay out its memory, or null when RetroAchievements has no achievements for the
 * platform. Several platforms share a console, such as the NES and the Famicom.
 *
 * The table is RomM's (`RA_PLATFORM_LIST` in RomM 5.4), keyed by RomM's platform slugs.
 */
fun retroAchievementsConsole(platform: PlatformSlug): Int? = Consoles[platform.value]

private val Consoles: Map<String, Int> =
  mapOf(
    "3do" to 43,
    "acpc" to 37,
    "appleii" to 38,
    "arcade" to 27,
    "arcadia-2001" to 73,
    "arduboy" to 71,
    "atari2600" to 25,
    "atari7800" to 51,
    "atari-jaguar-cd" to 77,
    "colecovision" to 44,
    "dc" to 40,
    "elektor" to 75,
    "fairchild-channel-f" to 57,
    "gb" to 4,
    "gba" to 5,
    "gbc" to 6,
    "gamegear" to 15,
    "ngc" to 16,
    "genesis" to 1,
    "intellivision" to 45,
    "interton-vc-4000" to 74,
    "jaguar" to 17,
    "lynx" to 13,
    "msx" to 29,
    "msx2" to 29,
    "mega-duck-slash-cougar-boy" to 69,
    "nes" to 7,
    "famicom" to 7,
    "fds" to 81,
    "neo-geo-cd" to 56,
    "neogeoaes" to 27,
    "neogeomvs" to 27,
    "neo-geo-pocket" to 14,
    "neo-geo-pocket-color" to 14,
    "n64" to 2,
    "nds" to 18,
    "nintendo-dsi" to 78,
    "odyssey-2" to 23,
    "pc-8800-series" to 47,
    "pc-fx" to 49,
    "psp" to 41,
    "psx" to 12,
    "ps2" to 21,
    "pokemon-mini" to 24,
    "saturn" to 39,
    "sega32" to 10,
    "segacd" to 9,
    "sms" to 11,
    "sg1000" to 33,
    "snes" to 3,
    "sfam" to 3,
    "turbografx-cd" to 76,
    "tg16" to 8,
    "supergrafx" to 8,
    "uzebox" to 80,
    "vectrex" to 46,
    "virtualboy" to 28,
    "wasm-4" to 72,
    "supervision" to 63,
    "wii" to 19,
    "wonderswan" to 53,
    "wonderswan-color" to 53,
  )
