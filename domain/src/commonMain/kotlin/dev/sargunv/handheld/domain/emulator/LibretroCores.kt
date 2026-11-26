// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformCatalog.ARCADE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.DREAMCAST
import dev.sargunv.handheld.domain.platform.PlatformCatalog.FDS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GAMECUBE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GAME_GEAR
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GB
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBC
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GENESIS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.MASTER_SYSTEM
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N3DS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N64
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N64DD
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NDS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NES
import dev.sargunv.handheld.domain.platform.PlatformCatalog.POKEMON_MINI
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS3
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSP
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSX
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SATURN
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SEGA_32X
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SEGA_CD
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SNES
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SWITCH
import dev.sargunv.handheld.domain.platform.PlatformCatalog.TG16
import dev.sargunv.handheld.domain.platform.PlatformCatalog.TG_CD
import dev.sargunv.handheld.domain.platform.PlatformCatalog.VITA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.WII
import dev.sargunv.handheld.domain.platform.PlatformCatalog.WII_U
import dev.sargunv.handheld.domain.platform.PlatformCatalog.XBOX
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * A libretro core, named as the libretro buildbot names its library, such as `snes9x_libretro`.
 *
 * [androidLibrary] is the name on the Android buildbot where it differs, for cores built per
 * graphics API there. [retroArchFolder] is the folder RetroArch sorts the core's saves and states
 * into. [source] is where its Android builds come from.
 *
 * [stackedScreensOptions] are the core options that make a two-screen system's core draw its
 * screens stacked in one frame, top above bottom, each in its own half of the frame, with no gap,
 * so the built-in session can show each half on a screen of its own. They're empty for cores that
 * draw one screen. [lowerScreenWidthShare] is the bottom screen's share of that frame's width, when
 * the core draws it narrower than the top screen and centred under it, so the session shows only
 * its columns; null means the bottom screen spans the frame's width.
 */
data class LibretroCore(
  val library: String,
  val androidLibrary: String = library,
  val retroArchFolder: String,
  val source: CoreSource = CoreSource.Buildbot,
  val stackedScreensOptions: Map<String, String> = emptyMap(),
  val lowerScreenWidthShare: Float? = null,
)

/** Where a core's Android builds come from, as a zip holding the core's library. */
sealed interface CoreSource {
  /** The libretro buildbot's nightly builds, rebuilt every night with no history kept. */
  data object Buildbot : CoreSource

  /**
   * The latest release of the GitHub repository [repository], such as `owner/name`, whose assets
   * include one per ABI named [assetPrefix], the ABI, a dash, the release's version and `.zip`.
   */
  data class GitHubRelease(val repository: String, val assetPrefix: String) : CoreSource {
    /** The asset among [names] that holds the build for [abi], or null when there's none. */
    fun assetFor(abi: String, names: List<String>): String? = names.firstOrNull {
      it.startsWith("$assetPrefix$abi-") && it.endsWith(".zip")
    }
  }
}

/** The libretro core recommended for each platform, for the built-in session and for RetroArch. */
object LibretroCores {
  private val mesen = LibretroCore("mesen_libretro", retroArchFolder = "Mesen")
  private val snes9x = LibretroCore("snes9x_libretro", retroArchFolder = "Snes9x")
  private val mupen64plusNext =
    LibretroCore(
      "mupen64plus_next_libretro",
      androidLibrary = "mupen64plus_next_gles3_libretro",
      retroArchFolder = "Mupen64Plus-Next",
    )
  private val gambatte = LibretroCore("gambatte_libretro", retroArchFolder = "Gambatte")
  private val mgba = LibretroCore("mgba_libretro", retroArchFolder = "mGBA")
  private val melonDsDs =
    LibretroCore(
      "melondsds_libretro",
      retroArchFolder = "melonDS DS",
      stackedScreensOptions =
        mapOf(
          "melonds_number_of_screen_layouts" to "1",
          "melonds_screen_layout1" to "top-bottom",
          "melonds_screen_gap" to "0",
        ),
    )
  // Azahar publishes this core with each of its releases, so it comes from there rather than from
  // the buildbot's nightlies.
  private val azahar =
    LibretroCore(
      "azahar_libretro",
      retroArchFolder = "Azahar",
      source = CoreSource.GitHubRelease("azahar-emu/azahar", "azahar-libretro-android-"),
      // Its default layout stacks the 400x240 top screen above the 320x240 bottom one, centered,
      // with no gap. Stereo 3D would draw each eye beside the other, so it stays off.
      stackedScreensOptions =
        mapOf(
          "citra_layout_option" to "default",
          "citra_swap_screen" to "Top",
          "citra_render_3d" to "off",
        ),
      lowerScreenWidthShare = 320f / 400f,
    )
  private val dolphin = LibretroCore("dolphin_libretro", retroArchFolder = "dolphin-emu")
  private val beetlePsxHw =
    LibretroCore("mednafen_psx_hw_libretro", retroArchFolder = "Beetle PSX HW")
  private val ppsspp = LibretroCore("ppsspp_libretro", retroArchFolder = "PPSSPP")
  private val genesisPlusGx =
    LibretroCore("genesis_plus_gx_libretro", retroArchFolder = "Genesis Plus GX")
  private val picoDrive = LibretroCore("picodrive_libretro", retroArchFolder = "PicoDrive")
  private val beetleSaturn =
    LibretroCore("mednafen_saturn_libretro", retroArchFolder = "Beetle Saturn")
  private val flycast = LibretroCore("flycast_libretro", retroArchFolder = "Flycast")
  private val beetlePce = LibretroCore("mednafen_pce_libretro", retroArchFolder = "Beetle PCE")
  private val pokeMini = LibretroCore("pokemini_libretro", retroArchFolder = "PokeMini")
  private val fbNeo = LibretroCore("fbneo_libretro", retroArchFolder = "FinalBurn Neo")

  /**
   * Every catalog platform that runs as itself, with its recommended core, or null where no
   * libretro core runs it well and a standalone emulator is the way to play.
   */
  val recommended: Map<PlatformSlug, LibretroCore?> =
    mapOf(
      NES to mesen,
      FDS to mesen,
      SNES to snes9x,
      N64 to mupen64plusNext,
      // The 64DD needs its boot ROM at system/Mupen64plus/IPL.n64.
      N64DD to mupen64plusNext,
      GB to gambatte,
      GBC to gambatte,
      GBA to mgba,
      NDS to melonDsDs,
      N3DS to azahar,
      GAMECUBE to dolphin,
      WII to dolphin,
      WII_U to null,
      SWITCH to null,
      PSX to beetlePsxHw,
      PS2 to null,
      PS3 to null,
      PSP to ppsspp,
      VITA to null,
      GENESIS to genesisPlusGx,
      MASTER_SYSTEM to genesisPlusGx,
      GAME_GEAR to genesisPlusGx,
      SEGA_CD to genesisPlusGx,
      SEGA_32X to picoDrive,
      SATURN to beetleSaturn,
      DREAMCAST to flycast,
      TG16 to beetlePce,
      TG_CD to beetlePce,
      POKEMON_MINI to pokeMini,
      XBOX to null,
      ARCADE to fbNeo,
    )

  /**
   * The platforms whose libretro core is a fallback: an installed standalone emulator plays them
   * better, so it comes first, and the built-in session only when none is installed.
   */
  private val standaloneFirst = setOf(N3DS, GAMECUBE, WII, PSP)

  /** Whether an installed standalone emulator plays [platform] before its libretro core does. */
  fun prefersStandalone(platform: PlatformSlug): Boolean =
    PlatformCatalog.emulatedAs(platform) in standaloneFirst

  /** The core recommended for [platform], or null when there's none or the platform is unknown. */
  fun recommendedFor(platform: PlatformSlug): LibretroCore? =
    recommended[PlatformCatalog.emulatedAs(platform)]

  /** Every core recommended for some platform. */
  val all: List<LibretroCore> = recommended.values.filterNotNull().distinct()

  /** The known core whose library is [library], or null when there's none. */
  fun byLibrary(library: String): LibretroCore? = all.firstOrNull { it.library == library }

  /**
   * The core that runs [platform]: the one [overrides] names for it by library, when that's a known
   * core, or else the recommended one.
   */
  fun coreFor(platform: PlatformSlug, overrides: Map<PlatformSlug, String>): LibretroCore? =
    overrides[platform]?.let(::byLibrary) ?: recommendedFor(platform)
}
