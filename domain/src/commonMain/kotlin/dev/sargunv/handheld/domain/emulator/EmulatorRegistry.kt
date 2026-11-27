// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.launch.ExtraValue
import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.launch.LaunchRecipe
import dev.sargunv.handheld.domain.launch.RomData
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

/** The Android emulators this launcher knows, with how to launch each. */
object EmulatorRegistry {
  private val retroArch =
    LaunchRecipe(
      action = IntentAction.MAIN,
      activity = "com.retroarch.browser.retroactivity.RetroActivityFuture",
      categories = emptyList(),
      data = null,
      mimeType = null,
      extras =
        mapOf(
          "ROM" to ExtraValue.RomPath,
          "LIBRETRO" to ExtraValue.Text("/data/data/{package}/cores/{core}_android.so"),
          "CONFIGFILE" to ExtraValue.Text("{storage}/Android/data/{package}/files/retroarch.cfg"),
          "IME" to ExtraValue.Text("com.android.inputmethod.latin/.LatinIME"),
          "DATADIR" to ExtraValue.Text("/data/data/{package}"),
          "SDCARD" to ExtraValue.Text("{storage}"),
          "EXTERNAL" to ExtraValue.Text("{storage}/Android/data/{package}/files"),
        ),
      flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
      grantRom = true,
    )

  private val mupen = LaunchRecipe(activity = "paulscode.android.mupen64plusae.SplashActivity")

  private val dolphin =
    LaunchRecipe.withExtras(
      action = IntentAction.MAIN,
      activity = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
      extras = mapOf("AutoStartFile" to ExtraValue.RomUri),
    )

  private val citraActivity = "org.citra.citra_emu.activities.EmulationActivity"

  private val yuzuActivity = "org.yuzu.yuzu_emu.activities.EmulationActivity"

  private val vita3k =
    LaunchRecipe(
      action = IntentAction.MAIN,
      activity = "org.vita3k.emulator.Emulator",
      categories = emptyList(),
      data = null,
      mimeType = null,
      extras = mapOf("AppStartParameters" to ExtraValue.VitaTitleArgs),
      flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
      stopBeforeLaunch = true,
    )

  private val melonDsActivity = "me.magnum.melonds.ui.emulator.EmulatorActivity"

  private fun pizzaBoy(packageName: String) =
    LaunchRecipe.withExtras(
      action = IntentAction.MAIN,
      activity = "$packageName.MainActivity",
      extras = mapOf("rom_uri" to ExtraValue.RomUriString),
    )

  private val ppsspp =
    LaunchRecipe(activity = "org.ppsspp.ppsspp.PpssppActivity", method = LaunchMethod.Shell)

  private val yabaSanshiro =
    LaunchRecipe(
      activity = "org.uoyabause.android.Yabause",
      extras =
        mapOf(
          "org.uoyabause.android.FileNameEx" to ExtraValue.RomPath,
          "org.uoyabause.android.FileNameUri" to ExtraValue.RomUriString,
        ),
    )

  private val armsx3 =
    LaunchRecipe.withExtras(
      action = IntentAction.MAIN,
      activity = "com.armsx2.BootSplashActivity",
      extras = mapOf("path" to ExtraValue.RomPath),
    )

  /** Phobos's names for the platforms it runs. */
  private val phobosPlatforms: Map<PlatformSlug, String> =
    mapOf(
      NES to "nes",
      FDS to "fds",
      SNES to "snes",
      N64 to "n64",
      N64DD to "n64dd",
      GB to "gb",
      GBC to "gbc",
      GBA to "gba",
      MASTER_SYSTEM to "sms",
      GENESIS to "genesis",
      SEGA_CD to "scd",
      GAME_GEAR to "gg",
      PSX to "psx",
      TG16 to "tg16",
    )

  val all: List<KnownEmulator> =
    listOf(
      KnownEmulator(
        id = EmulatorId("retroarch"),
        displayName = "RetroArch",
        packageName = "com.retroarch",
        recipe = retroArch,
        packagePatterns = listOf("com.retroarch*"),
      ),
      KnownEmulator(
        id = EmulatorId("retroarch_64"),
        displayName = "RetroArch (64-bit)",
        packageName = "com.retroarch.aarch64",
        recipe = retroArch,
      ),
      KnownEmulator(
        id = EmulatorId("retroarch_32"),
        displayName = "RetroArch (32-bit)",
        packageName = "com.retroarch.ra32",
        recipe = retroArch,
      ),
      KnownEmulator(
        id = EmulatorId("mupen64plus_fz"),
        displayName = "Mupen64Plus FZ",
        packageName = "org.mupen64plusae.v3.fzurita",
        recipe = mupen,
        packagePatterns = listOf("org.mupen64plusae.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("mupen64plus_fz_pro"),
        displayName = "M64Plus FZ Pro",
        packageName = "org.mupen64plusae.v3.fzurita.pro",
        recipe = mupen,
      ),
      KnownEmulator(
        id = EmulatorId("m64pro_fzx_plus"),
        displayName = "M64Pro FZX Plus+",
        packageName = "com.m64.fx.plus.emulate",
        recipe = mupen,
      ),
      KnownEmulator(
        id = EmulatorId("dolphin"),
        displayName = "Dolphin",
        packageName = "org.dolphinemu.dolphinemu",
        recipe = dolphin.copy(method = LaunchMethod.Shell),
        packagePatterns = listOf("org.dolphinemu.*"),
        forkRecipe = dolphin,
      ),
      KnownEmulator(
        id = EmulatorId("dolphin_handheld"),
        displayName = "Dolphin (Handheld)",
        packageName = "org.dolphinemu.handheld",
        recipe = dolphin,
      ),
      KnownEmulator(
        id = EmulatorId("dolphin_cs"),
        displayName = "DolphinCS",
        packageName = "com.joeyos.dolphinemu",
        recipe = dolphin,
      ),
      // SapphireRhodonite's dual-screen fork ships under the same package, so it can't be told
      // apart from this one, and launches the same way.
      KnownEmulator(
        id = EmulatorId("cemu"),
        displayName = "Cemu",
        packageName = "info.cemu.cemu",
        recipe = LaunchRecipe(activity = "info.cemu.cemu.emulation.EmulationActivity"),
      ),
      // Discontinued. Its family pattern covers the forks that kept its package namespace.
      KnownEmulator(
        id = EmulatorId("citra"),
        displayName = "Citra",
        packageName = "org.citra.citra_emu",
        recipe =
          LaunchRecipe(
            activity = citraActivity,
            extras = mapOf("SelectedGame" to ExtraValue.RomPath),
          ),
        packagePatterns = listOf("org.citra.*", "org.gamerytb.citra.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("citra_mmj"),
        displayName = "Citra MMJ",
        packageName = "org.citra.emu",
        recipe =
          LaunchRecipe(
            activity = "org.citra.emu.ui.EmulationActivity",
            extras = mapOf("GamePath" to ExtraValue.RomPath),
          ),
      ),
      // The vanilla APK moved from io.github.lime3ds.android to org.azahar_emu.azahar, and the Play
      // Store build may keep the old package. Activities live in Citra's namespace.
      KnownEmulator(
        id = EmulatorId("azahar"),
        displayName = "Azahar",
        packageName = "org.azahar_emu.azahar",
        recipe =
          LaunchRecipe(
            activity = citraActivity,
            extras = mapOf("SelectedGame" to ExtraValue.RomPath),
            method = LaunchMethod.Shell,
          ),
        packagePatterns =
          listOf("org.azahar_emu.*", "io.github.lime3ds.*", "io.github.azahar_emu.*"),
        forkRecipe =
          LaunchRecipe(
            activity = citraActivity,
            extras = mapOf("SelectedGame" to ExtraValue.RomPath),
          ),
        drawsBothScreens = true,
      ),
      KnownEmulator(
        id = EmulatorId("borked3ds"),
        displayName = "Borked3DS",
        packageName = "io.github.borked3ds.android",
        recipe =
          LaunchRecipe(activity = "io.github.borked3ds.android.activities.EmulationActivity"),
        packagePatterns = listOf("io.github.borked3ds.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("eden"),
        displayName = "Eden",
        packageName = "dev.eden.eden_emulator",
        recipe = LaunchRecipe(activity = yuzuActivity, method = LaunchMethod.Shell),
        packagePatterns = listOf("dev.eden.*", "dev.legacy.eden*", "org.eden.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("eden_duo"),
        displayName = "Eden Duo",
        packageName = "dev.igawa6.edenduo",
        recipe = LaunchRecipe(activity = yuzuActivity, method = LaunchMethod.Shell),
      ),
      KnownEmulator(
        id = EmulatorId("lemon"),
        displayName = "Lemon",
        packageName = "dev.lemon.lemon_emulator",
        recipe =
          LaunchRecipe(
            activity = "dev.lemon.lemon_emu.activities.EmulationActivity",
            method = LaunchMethod.Shell,
          ),
      ),
      KnownEmulator(
        id = EmulatorId("citron"),
        displayName = "Citron",
        packageName = "org.citron.emu",
        recipe = LaunchRecipe(activity = "org.citron.citron_emu.activities.EmulationActivity"),
        packagePatterns = listOf("org.citron.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("sudachi"),
        displayName = "Sudachi",
        packageName = "org.sudachi.sudachi_emu",
        recipe = LaunchRecipe(activity = "org.sudachi.sudachi_emu.activities.EmulationActivity"),
        packagePatterns = listOf("org.sudachi.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("ryujinx"),
        displayName = "Ryujinx",
        packageName = "org.ryujinx.android",
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.ryujinx.*"),
      ),
      KnownEmulator(
        id = EmulatorId("yuzu"),
        displayName = "Yuzu",
        packageName = "org.yuzu.yuzu_emu",
        recipe = LaunchRecipe(activity = yuzuActivity),
        packagePatterns = listOf("org.yuzu.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("strato"),
        displayName = "Strato",
        packageName = "org.stratoemu.strato",
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.stratoemu.*"),
      ),
      KnownEmulator(
        id = EmulatorId("skyline"),
        displayName = "Skyline",
        packageName = "skyline.emu",
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("skyline.*", "emu.skyline.*"),
      ),
      KnownEmulator(
        id = EmulatorId("kenjinx"),
        displayName = "Kenji-NX",
        packageName = "org.kenjinx.android",
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.kenjinx.*"),
      ),
      KnownEmulator(
        id = EmulatorId("drastic"),
        displayName = "DraStic",
        packageName = "com.dsemu.drastic",
        recipe =
          LaunchRecipe(
            activity = "com.dsemu.drastic.DraSticActivity",
            categories = emptyList(),
            // DraStic reads a file URI's decoded path, and opens nothing for a bare path.
            data = RomData.FileUri,
            mimeType = null,
            flags = setOf(LaunchFlag.ClearTask, LaunchFlag.ClearTop, LaunchFlag.NoHistory),
            method = LaunchMethod.Shell,
          ),
      ),
      KnownEmulator(
        id = EmulatorId("seedlessds"),
        displayName = "SeedlessDS",
        packageName = "com.seedlessds.app",
        recipe =
          LaunchRecipe(
            activity = "com.seedlessds.app.LaunchGame",
            extras = mapOf("rom_path" to ExtraValue.RomPath),
          ),
      ),
      KnownEmulator(
        id = EmulatorId("melonds"),
        displayName = "melonDS",
        packageName = "me.magnum.melonds",
        recipe =
          LaunchRecipe.withExtras(
            action = "me.magnum.melonds.LAUNCH_ROM",
            activity = melonDsActivity,
            extras = mapOf("uri" to ExtraValue.RomUri),
            data = RomData.ProviderUri,
          ),
        packagePatterns = listOf("me.magnum.melonds*", "me.magnum.melondualds*"),
        forkRecipe =
          LaunchRecipe(activity = melonDsActivity, extras = mapOf("PATH" to ExtraValue.RomPath)),
      ),
      // SapphireRhodonite's dual-screen fork, first MelonDualDS, now WatermelonDS, still under its
      // first package name.
      KnownEmulator(
        id = EmulatorId("melondualds"),
        displayName = "WatermelonDS",
        packageName = "me.magnum.melondualds",
        recipe =
          LaunchRecipe.withExtras(
            action = "me.magnum.melondualds.LAUNCH_ROM",
            activity = melonDsActivity,
            extras = mapOf("uri" to ExtraValue.RomUri),
            data = RomData.ProviderUri,
          ),
        drawsBothScreens = true,
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_ds"),
        displayName = "Pizza Boy Double Slice",
        packageName = "com.pizzaemulators.pbds",
        recipe = pizzaBoy("com.pizzaemulators.pbds"),
      ),
      KnownEmulator(
        id = EmulatorId("pokedaisy"),
        displayName = "PokeDaisy",
        packageName = "com.pokedaisy.app",
        recipe =
          LaunchRecipe.withExtras(
            action = IntentAction.MAIN,
            activity = "com.pokedaisy.app.LaunchActivity",
            extras = mapOf("rom" to ExtraValue.RomPath),
          ),
        drawsBothScreens = true,
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gba_pro"),
        displayName = "Pizza Boy GBA Pro",
        packageName = "it.dbtecno.pizzaboygbapro",
        recipe = pizzaBoy("it.dbtecno.pizzaboygbapro"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gba"),
        displayName = "Pizza Boy GBA",
        packageName = "it.dbtecno.pizzaboygba",
        recipe = pizzaBoy("it.dbtecno.pizzaboygba"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gb_pro"),
        displayName = "Pizza Boy GB Pro",
        packageName = "it.dbtecno.pizzaboypro",
        recipe = pizzaBoy("it.dbtecno.pizzaboypro"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gb"),
        displayName = "Pizza Boy GB",
        packageName = "it.dbtecno.pizzaboy",
        recipe = pizzaBoy("it.dbtecno.pizzaboy"),
      ),
      KnownEmulator(
        id = EmulatorId("linkboy"),
        displayName = "LinkBoy",
        packageName = "com.pixelrespawn.linkboy",
        recipe =
          LaunchRecipe(
            categories = emptyList(),
            data = RomData.Scheme("linkboy", "emulator"),
            mimeType = null,
            flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
          ),
      ),
      KnownEmulator(
        id = EmulatorId("super_zsnes"),
        displayName = "SUPER ZSNES",
        packageName = "com.zsnes.superzsnes",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("duckstation"),
        displayName = "DuckStation",
        packageName = "com.github.stenzek.duckstation",
        recipe =
          LaunchRecipe.withExtras(
            action = IntentAction.MAIN,
            activity = "com.github.stenzek.duckstation.EmulationActivity",
            extras =
              mapOf(
                // Builds before 10000 can't open a plain path from shared storage.
                "bootPath" to ExtraValue.RomPathSinceBuild(10_000),
                "resumeState" to ExtraValue.Bool(false),
              ),
          ),
        packagePatterns = listOf("com.github.stenzek.duckstation*"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx1"),
        displayName = "ARMSX1",
        packageName = "com.nanodata.armsx",
        recipe = armsx3,
      ),
      // AetherSX2, discontinued, shares this package and launches the same way.
      KnownEmulator(
        id = EmulatorId("nethersx2"),
        displayName = "NetherSX2",
        packageName = "xyz.aethersx2.android",
        recipe =
          LaunchRecipe.withExtras(
            action = IntentAction.MAIN,
            activity = "xyz.aethersx2.android.EmulationActivity",
            extras = mapOf("bootPath" to ExtraValue.RomUriString),
          ),
        packagePatterns = listOf("xyz.aethersx2.*"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx2"),
        displayName = "ARMSX2",
        packageName = "com.armsx2",
        recipe = LaunchRecipe(activity = "com.armsx2.MainActivity", method = LaunchMethod.Shell),
        packagePatterns = listOf("com.armsx2.*"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx2_play"),
        displayName = "ARMSX2 (Play Store)",
        packageName = "come.nanodata.armsx2",
        recipe =
          LaunchRecipe(
            activity = "kr.co.iefriends.pcsx2.activities.MainActivity",
            method = LaunchMethod.Shell,
          ),
        packagePatterns = listOf("come.nanodata.armsx2.*"),
      ),
      // A native port of the Jak and Daxter games, which keeps their saves on a PS2 folder card.
      KnownEmulator(
        id = EmulatorId("pocketgoal"),
        displayName = "PocketGOAL",
        packageName = "dev.sargunv.pocketgoal",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("psx2"),
        displayName = "PSX2",
        packageName = "com.izzy2lost.psx2",
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("com.izzy2lost.*"),
      ),
      KnownEmulator(
        id = EmulatorId("pcsx2"),
        displayName = "PCSX2",
        packageName = "net.pcsx2.emulator",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp"),
        displayName = "PPSSPP",
        packageName = "org.ppsspp.ppsspp",
        recipe = ppsspp,
        packagePatterns = listOf("org.ppsspp.*"),
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp_gold"),
        displayName = "PPSSPP Gold",
        packageName = "org.ppsspp.ppssppgold",
        recipe = ppsspp,
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp_legacy"),
        displayName = "PPSSPP Legacy",
        packageName = "org.ppsspp.ppsspplegacy",
        recipe = ppsspp,
      ),
      KnownEmulator(
        id = EmulatorId("vita3k_zx"),
        displayName = "Vita3K ZX",
        packageName = "org.vita3k.emulator.ikhoeyZX",
        recipe = vita3k,
      ),
      KnownEmulator(
        id = EmulatorId("vita3k"),
        displayName = "Vita3K",
        packageName = "org.vita3k.emulator",
        recipe = vita3k,
        packagePatterns = listOf("org.vita3k.emulator*"),
      ),
      KnownEmulator(
        id = EmulatorId("vita3k_plus"),
        displayName = "Vita3K+",
        packageName = "org.vita3kplus.emulator",
        recipe = vita3k,
      ),
      // Android 13 and later reject explicit-activity launches of some Redream builds:
      // https://github.com/TapiocaFox/Daijishou/issues/487
      KnownEmulator(
        id = EmulatorId("redream"),
        displayName = "Redream",
        packageName = "io.recompiled.redream",
        recipe = LaunchRecipe(activity = "io.recompiled.redream.MainActivity"),
      ),
      KnownEmulator(
        id = EmulatorId("flycast"),
        displayName = "Flycast",
        packageName = "com.flycast.emulator",
        recipe =
          LaunchRecipe(
            activity = "com.flycast.emulator.MainActivity",
            // Flycast opens the URI's string as a path unless it's a content URI.
            data = RomData.Path,
            method = LaunchMethod.Shell,
          ),
      ),
      KnownEmulator(
        id = EmulatorId("yabasanshiro"),
        displayName = "Yaba Sanshiro 2",
        packageName = "org.devmiyax.yabasanshioro2",
        recipe = yabaSanshiro,
      ),
      KnownEmulator(
        id = EmulatorId("yabasanshiro_pro"),
        displayName = "Yaba Sanshiro 2 Pro",
        packageName = "org.devmiyax.yabasanshioro2.pro",
        recipe = yabaSanshiro,
      ),
      KnownEmulator(
        id = EmulatorId("saturn_emu"),
        displayName = "Saturn.emu",
        packageName = "com.explusalpha.SaturnEmu",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("md_emu"),
        displayName = "MD.emu",
        packageName = "com.explusalpha.MdEmu",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("mame4droid"),
        displayName = "MAME4droid",
        packageName = "com.seleuco.mame4droid",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("fbalpha"),
        displayName = "FinalBurn Alpha",
        packageName = "com.bangkokfusion.finalburn",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("hakux"),
        displayName = "hakuX",
        packageName = "com.rfandango.haku_x",
        recipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("armsx3"),
        displayName = "ARMSX3",
        packageName = "com.armsx3",
        recipe = armsx3,
      ),
      KnownEmulator(
        id = EmulatorId("armsx3_play"),
        displayName = "ARMSX3 (Play Store)",
        packageName = "com.armsx3.play",
        recipe = armsx3,
      ),
      KnownEmulator(
        id = EmulatorId("aps3e"),
        displayName = "aPS3e",
        packageName = "aenu.aps3e",
        recipe =
          LaunchRecipe.withExtras(
            action = "aenu.intent.action.APS3E",
            activity = "aenu.aps3e.EmulatorActivity",
            extras = mapOf("iso_uri" to ExtraValue.RomUriString),
          ),
      ),
      KnownEmulator(
        id = EmulatorId("phobos"),
        displayName = "Phobos",
        packageName = "com.phobos.emulator",
        recipe =
          LaunchRecipe(
            activity = "com.phobos.emulator.MainActivity",
            extras = mapOf("platform" to ExtraValue.PlatformName(phobosPlatforms)),
          ),
      ),
    )

  private val retroArchIds =
    listOf(EmulatorId("retroarch"), EmulatorId("retroarch_64"), EmulatorId("retroarch_32"))

  private fun ids(vararg ids: String) = ids.map(::EmulatorId)

  /**
   * The known emulators that run each platform, best first: the order the fallback rule tries them
   * in when nothing names an emulator. An app that plays only some of a platform's games, such as a
   * port of one game, is left out, so nothing picks or suggests it unless a rule names it.
   */
  val preference: Map<PlatformSlug, List<EmulatorId>> =
    mapOf(
      NES to retroArchIds + ids("phobos"),
      FDS to retroArchIds + ids("phobos"),
      SNES to retroArchIds + ids("super_zsnes", "phobos"),
      N64 to
        ids("mupen64plus_fz", "mupen64plus_fz_pro", "m64pro_fzx_plus") +
          retroArchIds +
          ids("phobos"),
      N64DD to ids("phobos"),
      GB to
        ids("pizza_boy_gb_pro", "pizza_boy_gb", "linkboy") +
          retroArchIds +
          ids("pokedaisy", "phobos"),
      GBC to
        ids("pizza_boy_gb_pro", "pizza_boy_gb", "linkboy") +
          retroArchIds +
          ids("pokedaisy", "phobos"),
      GBA to
        ids("pizza_boy_gba_pro", "pizza_boy_gba", "linkboy") +
          retroArchIds +
          ids("pokedaisy", "phobos"),
      NDS to
        ids("drastic", "seedlessds", "melonds", "melondualds", "pizza_boy_ds") +
          retroArchIds +
          ids("pokedaisy"),
      N3DS to ids("azahar", "citra_mmj", "borked3ds", "citra") + retroArchIds,
      GAMECUBE to ids("dolphin", "dolphin_handheld", "dolphin_cs") + retroArchIds,
      WII to ids("dolphin", "dolphin_handheld", "dolphin_cs") + retroArchIds,
      WII_U to ids("cemu"),
      SWITCH to
        ids(
          "eden",
          "eden_duo",
          "lemon",
          "citron",
          "sudachi",
          "ryujinx",
          "kenjinx",
          "yuzu",
          "strato",
          "skyline",
        ),
      PSX to ids("duckstation", "armsx1") + retroArchIds + ids("phobos"),
      PS2 to ids("nethersx2", "armsx2", "armsx2_play", "psx2", "pcsx2"),
      PS3 to ids("armsx3", "armsx3_play", "aps3e"),
      PSP to ids("ppsspp", "ppsspp_gold", "ppsspp_legacy") + retroArchIds,
      VITA to ids("vita3k_zx", "vita3k", "vita3k_plus"),
      GENESIS to ids("md_emu") + retroArchIds + ids("phobos"),
      MASTER_SYSTEM to ids("md_emu") + retroArchIds + ids("phobos"),
      GAME_GEAR to ids("md_emu") + retroArchIds + ids("phobos"),
      SEGA_CD to ids("md_emu") + retroArchIds + ids("phobos"),
      SEGA_32X to ids("md_emu") + retroArchIds,
      SATURN to ids("yabasanshiro", "yabasanshiro_pro", "saturn_emu") + retroArchIds,
      DREAMCAST to ids("redream", "flycast") + retroArchIds,
      TG16 to retroArchIds + ids("phobos"),
      TG_CD to retroArchIds,
      POKEMON_MINI to retroArchIds,
      XBOX to ids("hakux"),
      ARCADE to ids("mame4droid", "fbalpha") + retroArchIds + ids("flycast"),
    )

  private val byId: Map<EmulatorId, KnownEmulator> = all.associateBy { it.id }
  private val byPackage: Map<String, KnownEmulator> = all.associateBy { it.packageName }

  operator fun get(id: EmulatorId): KnownEmulator? = byId[id]

  /**
   * The known emulator [packageName] is: the one it belongs to exactly, else the first whose
   * patterns match it; null when it's none of them.
   */
  fun forPackage(packageName: String): KnownEmulator? =
    byPackage[packageName] ?: all.firstOrNull { it.matchesPattern(packageName) }

  /** The known emulators for [platform], best first. */
  fun forPlatform(platform: PlatformSlug): List<KnownEmulator> =
    preference[PlatformCatalog.emulatedAs(platform)].orEmpty().map { byId.getValue(it) }
}
