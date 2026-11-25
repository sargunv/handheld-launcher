package dev.sargunv.handheld.domain.platform

/** The platforms this launcher knows, keyed by their RomM slugs. */
object PlatformCatalog {
  val NES = PlatformSlug("nes")
  val FAMICOM = PlatformSlug("famicom")
  val FDS = PlatformSlug("fds")
  val SNES = PlatformSlug("snes")
  val SUPER_FAMICOM = PlatformSlug("sfam")
  val N64 = PlatformSlug("n64")
  val N64DD = PlatformSlug("64dd")
  val GB = PlatformSlug("gb")
  val GBC = PlatformSlug("gbc")
  val GBA = PlatformSlug("gba")
  val NDS = PlatformSlug("nds")
  val N3DS = PlatformSlug("3ds")
  val NEW_N3DS = PlatformSlug("new-nintendo-3ds")
  val GAMECUBE = PlatformSlug("ngc")
  val WII = PlatformSlug("wii")
  val WII_U = PlatformSlug("wiiu")
  val SWITCH = PlatformSlug("switch")
  val PSX = PlatformSlug("psx")
  val PS2 = PlatformSlug("ps2")
  val PS3 = PlatformSlug("ps3")
  val PSP = PlatformSlug("psp")
  val VITA = PlatformSlug("psvita")
  val GENESIS = PlatformSlug("genesis")
  val MASTER_SYSTEM = PlatformSlug("sms")
  val GAME_GEAR = PlatformSlug("gamegear")
  val SEGA_CD = PlatformSlug("segacd")
  val SEGA_32X = PlatformSlug("sega32")
  val SATURN = PlatformSlug("saturn")
  val DREAMCAST = PlatformSlug("dc")
  val TG16 = PlatformSlug("tg16")
  val TG_CD = PlatformSlug("turbografx-cd")
  val POKEMON_MINI = PlatformSlug("pokemon-mini")
  val XBOX = PlatformSlug("xbox")
  val ARCADE = PlatformSlug("arcade")

  val all: List<Platform> =
    listOf(
      Platform(NES, "Nintendo Entertainment System"),
      Platform(FAMICOM, "Family Computer", aliases = setOf("fc"), runsAs = NES),
      Platform(FDS, "Family Computer Disk System", aliases = setOf("famicom-disk-system")),
      Platform(SNES, "Super Nintendo Entertainment System"),
      Platform(SUPER_FAMICOM, "Super Famicom", aliases = setOf("sfc"), runsAs = SNES),
      Platform(N64, "Nintendo 64"),
      Platform(N64DD, "Nintendo 64DD", aliases = setOf("n64dd")),
      Platform(GB, "Game Boy"),
      Platform(GBC, "Game Boy Color"),
      Platform(GBA, "Game Boy Advance"),
      Platform(NDS, "Nintendo DS"),
      Platform(N3DS, "Nintendo 3DS", aliases = setOf("n3ds")),
      Platform(NEW_N3DS, "New Nintendo 3DS", runsAs = N3DS),
      Platform(GAMECUBE, "Nintendo GameCube", aliases = setOf("gc", "gamecube")),
      Platform(WII, "Wii"),
      Platform(WII_U, "Wii U"),
      Platform(SWITCH, "Nintendo Switch"),
      Platform(PSX, "PlayStation", aliases = setOf("ps1")),
      Platform(PS2, "PlayStation 2"),
      Platform(PS3, "PlayStation 3"),
      Platform(PSP, "PlayStation Portable"),
      Platform(VITA, "PlayStation Vita", aliases = setOf("vita")),
      Platform(GENESIS, "Sega Genesis", aliases = setOf("megadrive", "genesis-slash-megadrive")),
      Platform(MASTER_SYSTEM, "Sega Master System", aliases = setOf("mastersystem")),
      Platform(GAME_GEAR, "Sega Game Gear", aliases = setOf("gg")),
      Platform(SEGA_CD, "Sega CD", aliases = setOf("megacd")),
      Platform(SEGA_32X, "Sega 32X", aliases = setOf("sega32x", "32x")),
      Platform(SATURN, "Sega Saturn"),
      Platform(DREAMCAST, "Dreamcast", aliases = setOf("dreamcast")),
      Platform(TG16, "TurboGrafx-16", aliases = setOf("pcengine", "pce")),
      Platform(TG_CD, "TurboGrafx-CD", aliases = setOf("tg-cd", "pcenginecd")),
      Platform(POKEMON_MINI, "Pokémon mini", aliases = setOf("pokemini")),
      Platform(XBOX, "Xbox"),
      Platform(ARCADE, "Arcade"),
    )

  private val bySlugOrAlias: Map<String, Platform> = buildMap {
    for (platform in all) {
      put(platform.slug.value, platform)
      for (alias in platform.aliases) put(alias, platform)
    }
  }

  /** The platform [slug] names, by RomM slug or alias, ignoring case; null when it's unknown. */
  operator fun get(slug: String): Platform? = bySlugOrAlias[slug.lowercase()]

  operator fun get(slug: PlatformSlug): Platform? = get(slug.value)

  /** The platform whose emulators run [slug]; [slug] itself when the catalog doesn't know it. */
  fun emulatedAs(slug: PlatformSlug): PlatformSlug = get(slug)?.emulatedAs ?: slug
}
