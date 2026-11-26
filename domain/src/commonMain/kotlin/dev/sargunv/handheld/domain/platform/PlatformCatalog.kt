package dev.sargunv.handheld.domain.platform

/** The platforms this launcher knows, by their RomM slugs. */
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

  /** Regional twins, each with the platform whose emulators and cores run it. */
  private val runsAs: Map<PlatformSlug, PlatformSlug> =
    mapOf(FAMICOM to NES, SUPER_FAMICOM to SNES, NEW_N3DS to N3DS)

  /** The platform whose emulators and cores run [slug]: its twin, or [slug] itself. */
  fun emulatedAs(slug: PlatformSlug): PlatformSlug = runsAs[slug] ?: slug
}
