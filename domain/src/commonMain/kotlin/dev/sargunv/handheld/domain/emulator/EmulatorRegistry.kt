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
  private val retroArchPlatforms =
    setOf(
      NES,
      FDS,
      SNES,
      N64,
      GAMECUBE,
      WII,
      GB,
      GBC,
      GBA,
      NDS,
      N3DS,
      GENESIS,
      MASTER_SYSTEM,
      GAME_GEAR,
      SEGA_CD,
      SEGA_32X,
      PSX,
      PSP,
      SATURN,
      DREAMCAST,
      TG16,
      TG_CD,
      POKEMON_MINI,
      ARCADE,
    )

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

  private fun gitHub(repo: String) = ReleaseSource.GitHub(repo)

  private fun playStore(packageName: String) =
    "https://play.google.com/store/apps/details?id=$packageName"

  val all: List<KnownEmulator> =
    listOf(
      KnownEmulator(
        id = EmulatorId("retroarch"),
        displayName = "RetroArch",
        packageName = "com.retroarch",
        platforms = retroArchPlatforms,
        recipe = retroArch,
        packagePatterns = listOf("com.retroarch*"),
        downloadUrl = "https://www.retroarch.com/?page=platforms",
      ),
      KnownEmulator(
        id = EmulatorId("retroarch_64"),
        displayName = "RetroArch (64-bit)",
        packageName = "com.retroarch.aarch64",
        platforms = retroArchPlatforms,
        recipe = retroArch,
        downloadUrl = "https://www.retroarch.com/?page=platforms",
      ),
      KnownEmulator(
        id = EmulatorId("retroarch_32"),
        displayName = "RetroArch (32-bit)",
        packageName = "com.retroarch.ra32",
        platforms = retroArchPlatforms,
        recipe = retroArch,
        downloadUrl = "https://www.retroarch.com/?page=platforms",
      ),
      KnownEmulator(
        id = EmulatorId("mupen64plus_fz"),
        displayName = "Mupen64Plus FZ",
        packageName = "org.mupen64plusae.v3.fzurita",
        platforms = setOf(N64),
        recipe = mupen,
        packagePatterns = listOf("org.mupen64plusae.*"),
        forkRecipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("org.mupen64plusae.v3.fzurita"),
      ),
      KnownEmulator(
        id = EmulatorId("mupen64plus_fz_pro"),
        displayName = "M64Plus FZ Pro",
        packageName = "org.mupen64plusae.v3.fzurita.pro",
        platforms = setOf(N64),
        recipe = mupen,
        downloadUrl = playStore("org.mupen64plusae.v3.fzurita.pro"),
      ),
      KnownEmulator(
        id = EmulatorId("m64pro_fzx_plus"),
        displayName = "M64Pro FZX Plus+",
        packageName = "com.m64.fx.plus.emulate",
        platforms = setOf(N64),
        recipe = mupen,
        downloadUrl = playStore("com.m64.fx.plus.emulate"),
      ),
      KnownEmulator(
        id = EmulatorId("dolphin"),
        displayName = "Dolphin",
        packageName = "org.dolphinemu.dolphinemu",
        platforms = setOf(GAMECUBE, WII),
        recipe = dolphin.copy(method = LaunchMethod.Shell),
        packagePatterns = listOf("org.dolphinemu.*"),
        forkRecipe = dolphin,
        downloadUrl = playStore("org.dolphinemu.dolphinemu"),
      ),
      KnownEmulator(
        id = EmulatorId("dolphin_handheld"),
        displayName = "Dolphin (Handheld)",
        packageName = "org.dolphinemu.handheld",
        platforms = setOf(GAMECUBE, WII),
        recipe = dolphin,
        downloadUrl = "https://dolphin-emu.org/download/",
      ),
      KnownEmulator(
        id = EmulatorId("dolphin_cs"),
        displayName = "DolphinCS",
        packageName = "com.joeyos.dolphinemu",
        platforms = setOf(GAMECUBE, WII),
        recipe = dolphin,
        downloadUrl = "https://github.com/JoeysRetroHandhelds/DolphinCS/releases",
        releases = gitHub("JoeysRetroHandhelds/DolphinCS"),
      ),
      // SapphireRhodonite's dual-screen fork ships under the same package, so it can't be told
      // apart from this one, and launches the same way.
      KnownEmulator(
        id = EmulatorId("cemu"),
        displayName = "Cemu",
        packageName = "info.cemu.cemu",
        platforms = setOf(WII_U),
        recipe = LaunchRecipe(activity = "info.cemu.cemu.emulation.EmulationActivity"),
        downloadUrl = "https://github.com/SSimco/Cemu/releases",
        releases = gitHub("SSimco/Cemu"),
      ),
      // Discontinued. Its family pattern covers the forks that kept its package namespace.
      KnownEmulator(
        id = EmulatorId("citra"),
        displayName = "Citra",
        packageName = "org.citra.citra_emu",
        platforms = setOf(N3DS),
        recipe =
          LaunchRecipe(
            activity = citraActivity,
            extras = mapOf("SelectedGame" to ExtraValue.RomPath),
          ),
        packagePatterns = listOf("org.citra.*", "org.gamerytb.citra.*"),
        forkRecipe = LaunchRecipe.ViewRom,
        downloadUrl = "https://citra-emu.org/",
      ),
      KnownEmulator(
        id = EmulatorId("citra_mmj"),
        displayName = "Citra MMJ",
        packageName = "org.citra.emu",
        platforms = setOf(N3DS),
        recipe =
          LaunchRecipe(
            activity = "org.citra.emu.ui.EmulationActivity",
            extras = mapOf("GamePath" to ExtraValue.RomPath),
          ),
        downloadUrl = "https://github.com/weihuoya/citra/releases",
        releases = gitHub("weihuoya/citra"),
      ),
      // The vanilla APK moved from io.github.lime3ds.android to org.azahar_emu.azahar, and the Play
      // Store build may keep the old package. Activities live in Citra's namespace.
      KnownEmulator(
        id = EmulatorId("azahar"),
        displayName = "Azahar",
        packageName = "org.azahar_emu.azahar",
        platforms = setOf(N3DS),
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
        downloadUrl = "https://github.com/azahar-emu/azahar/releases",
        releases = gitHub("azahar-emu/azahar"),
      ),
      KnownEmulator(
        id = EmulatorId("borked3ds"),
        displayName = "Borked3DS",
        packageName = "io.github.borked3ds.android",
        platforms = setOf(N3DS),
        recipe =
          LaunchRecipe(activity = "io.github.borked3ds.android.activities.EmulationActivity"),
        packagePatterns = listOf("io.github.borked3ds.*"),
        forkRecipe = LaunchRecipe.ViewRom,
        downloadUrl = "https://github.com/Borked3DS/Borked3DS/releases",
        releases = gitHub("Borked3DS/Borked3DS"),
      ),
      KnownEmulator(
        id = EmulatorId("eden"),
        displayName = "Eden",
        packageName = "dev.eden.eden_emulator",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe(activity = yuzuActivity, method = LaunchMethod.Shell),
        packagePatterns = listOf("dev.eden.*", "dev.legacy.eden*", "org.eden.*"),
        forkRecipe = LaunchRecipe.ViewRom,
        downloadUrl = "https://git.eden-emu.dev/eden-emu/eden/releases",
        releases = ReleaseSource.Gitea("https://git.eden-emu.dev", "eden-emu/eden"),
      ),
      KnownEmulator(
        id = EmulatorId("eden_duo"),
        displayName = "Eden Duo",
        packageName = "dev.igawa6.edenduo",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe(activity = yuzuActivity, method = LaunchMethod.Shell),
        downloadUrl = "https://github.com/igawa6/eden-duo/releases",
        releases = gitHub("igawa6/eden-duo"),
      ),
      KnownEmulator(
        id = EmulatorId("lemon"),
        displayName = "Lemon",
        packageName = "dev.lemon.lemon_emulator",
        platforms = setOf(SWITCH),
        recipe =
          LaunchRecipe(
            activity = "dev.lemon.lemon_emu.activities.EmulationActivity",
            method = LaunchMethod.Shell,
          ),
        downloadUrl = "https://git.lemon-emu.org/lemon/Lemon-Project/releases",
        releases = ReleaseSource.Gitea("https://git.lemon-emu.org", "lemon/Lemon-Project"),
      ),
      KnownEmulator(
        id = EmulatorId("citron"),
        displayName = "Citron",
        packageName = "org.citron.emu",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe(activity = "org.citron.citron_emu.activities.EmulationActivity"),
        packagePatterns = listOf("org.citron.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("sudachi"),
        displayName = "Sudachi",
        packageName = "org.sudachi.sudachi_emu",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe(activity = "org.sudachi.sudachi_emu.activities.EmulationActivity"),
        packagePatterns = listOf("org.sudachi.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("ryujinx"),
        displayName = "Ryujinx",
        packageName = "org.ryujinx.android",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.ryujinx.*"),
      ),
      KnownEmulator(
        id = EmulatorId("yuzu"),
        displayName = "Yuzu",
        packageName = "org.yuzu.yuzu_emu",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe(activity = yuzuActivity),
        packagePatterns = listOf("org.yuzu.*"),
        forkRecipe = LaunchRecipe.ViewRom,
      ),
      KnownEmulator(
        id = EmulatorId("strato"),
        displayName = "Strato",
        packageName = "org.stratoemu.strato",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.stratoemu.*"),
      ),
      KnownEmulator(
        id = EmulatorId("skyline"),
        displayName = "Skyline",
        packageName = "skyline.emu",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("skyline.*", "emu.skyline.*"),
      ),
      KnownEmulator(
        id = EmulatorId("kenjinx"),
        displayName = "Kenji-NX",
        packageName = "org.kenjinx.android",
        platforms = setOf(SWITCH),
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("org.kenjinx.*"),
        downloadUrl = "https://git.ryujinx.app/kenji-nx/android/-/releases",
        releases = ReleaseSource.GitLab("https://git.ryujinx.app", "kenji-nx/android"),
      ),
      KnownEmulator(
        id = EmulatorId("drastic"),
        displayName = "DraStic",
        packageName = "com.dsemu.drastic",
        platforms = setOf(NDS),
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
        downloadUrl = playStore("com.dsemu.drastic"),
      ),
      KnownEmulator(
        id = EmulatorId("seedlessds"),
        displayName = "SeedlessDS",
        packageName = "com.seedlessds.app",
        platforms = setOf(NDS),
        recipe =
          LaunchRecipe(
            activity = "com.seedlessds.app.LaunchGame",
            extras = mapOf("rom_path" to ExtraValue.RomPath),
          ),
        downloadUrl = "https://github.com/SapphireRhodonite/SeedlessDS/releases",
        releases = gitHub("SapphireRhodonite/SeedlessDS"),
      ),
      KnownEmulator(
        id = EmulatorId("melonds"),
        displayName = "melonDS",
        packageName = "me.magnum.melonds",
        platforms = setOf(NDS),
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
        downloadUrl =
          "https://github.com/rafaelvcaetano/melonDS-android/releases/tag/nightly-release",
        releases = gitHub("rafaelvcaetano/melonDS-android"),
      ),
      // SapphireRhodonite's dual-screen fork, first MelonDualDS, now WatermelonDS, still under its
      // first package name.
      KnownEmulator(
        id = EmulatorId("melondualds"),
        displayName = "WatermelonDS",
        packageName = "me.magnum.melondualds",
        platforms = setOf(NDS),
        recipe =
          LaunchRecipe.withExtras(
            action = "me.magnum.melondualds.LAUNCH_ROM",
            activity = melonDsActivity,
            extras = mapOf("uri" to ExtraValue.RomUri),
            data = RomData.ProviderUri,
          ),
        drawsBothScreens = true,
        downloadUrl = "https://github.com/SapphireRhodonite/WatermelonDS/releases",
        releases = gitHub("SapphireRhodonite/WatermelonDS"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_ds"),
        displayName = "Pizza Boy Double Slice",
        packageName = "com.pizzaemulators.pbds",
        platforms = setOf(NDS),
        recipe = pizzaBoy("com.pizzaemulators.pbds"),
        downloadUrl = playStore("com.pizzaemulators.pbds"),
      ),
      KnownEmulator(
        id = EmulatorId("pokedaisy"),
        displayName = "PokeDaisy",
        packageName = "com.pokedaisy.app",
        platforms = setOf(GB, GBC, GBA, NDS),
        recipe =
          LaunchRecipe.withExtras(
            action = IntentAction.MAIN,
            activity = "com.pokedaisy.app.LaunchActivity",
            extras = mapOf("rom" to ExtraValue.RomPath),
          ),
        drawsBothScreens = true,
        downloadUrl = "https://github.com/lidor30/pokedaisy/releases",
        releases = gitHub("lidor30/pokedaisy"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gba_pro"),
        displayName = "Pizza Boy GBA Pro",
        packageName = "it.dbtecno.pizzaboygbapro",
        platforms = setOf(GBA),
        recipe = pizzaBoy("it.dbtecno.pizzaboygbapro"),
        downloadUrl = playStore("it.dbtecno.pizzaboygbapro"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gba"),
        displayName = "Pizza Boy GBA",
        packageName = "it.dbtecno.pizzaboygba",
        platforms = setOf(GBA),
        recipe = pizzaBoy("it.dbtecno.pizzaboygba"),
        downloadUrl = playStore("it.dbtecno.pizzaboygba"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gb_pro"),
        displayName = "Pizza Boy GB Pro",
        packageName = "it.dbtecno.pizzaboypro",
        platforms = setOf(GB, GBC),
        recipe = pizzaBoy("it.dbtecno.pizzaboypro"),
        downloadUrl = playStore("it.dbtecno.pizzaboypro"),
      ),
      KnownEmulator(
        id = EmulatorId("pizza_boy_gb"),
        displayName = "Pizza Boy GB",
        packageName = "it.dbtecno.pizzaboy",
        platforms = setOf(GB, GBC),
        recipe = pizzaBoy("it.dbtecno.pizzaboy"),
        downloadUrl = playStore("it.dbtecno.pizzaboy"),
      ),
      KnownEmulator(
        id = EmulatorId("linkboy"),
        displayName = "LinkBoy",
        packageName = "com.pixelrespawn.linkboy",
        platforms = setOf(GB, GBC, GBA),
        recipe =
          LaunchRecipe(
            categories = emptyList(),
            data = RomData.Scheme("linkboy", "emulator"),
            mimeType = null,
            flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
          ),
        downloadUrl = playStore("com.pixelrespawn.linkboy"),
      ),
      KnownEmulator(
        id = EmulatorId("super_zsnes"),
        displayName = "SUPER ZSNES",
        packageName = "com.zsnes.superzsnes",
        platforms = setOf(SNES),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("com.zsnes.superzsnes"),
      ),
      KnownEmulator(
        id = EmulatorId("duckstation"),
        displayName = "DuckStation",
        packageName = "com.github.stenzek.duckstation",
        platforms = setOf(PSX),
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
        downloadUrl = playStore("com.github.stenzek.duckstation"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx1"),
        displayName = "ARMSX1",
        packageName = "com.nanodata.armsx",
        platforms = setOf(PSX),
        recipe = armsx3,
        downloadUrl = "https://github.com/ARMSX2/ARMSX1/releases",
        releases = gitHub("ARMSX2/ARMSX1"),
      ),
      // AetherSX2, discontinued, shares this package and launches the same way.
      KnownEmulator(
        id = EmulatorId("nethersx2"),
        displayName = "NetherSX2",
        packageName = "xyz.aethersx2.android",
        platforms = setOf(PS2),
        recipe =
          LaunchRecipe.withExtras(
            action = IntentAction.MAIN,
            activity = "xyz.aethersx2.android.EmulationActivity",
            extras = mapOf("bootPath" to ExtraValue.RomUriString),
          ),
        packagePatterns = listOf("xyz.aethersx2.*"),
        downloadUrl = "https://github.com/Trixarian/NetherSX2-patch/releases",
        releases = gitHub("Trixarian/NetherSX2-patch"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx2"),
        displayName = "ARMSX2",
        packageName = "com.armsx2",
        platforms = setOf(PS2),
        recipe = LaunchRecipe(activity = "com.armsx2.MainActivity", method = LaunchMethod.Shell),
        packagePatterns = listOf("com.armsx2.*"),
        downloadUrl = "https://github.com/ARMSX2/ARMSX2/releases",
        releases = gitHub("ARMSX2/ARMSX2"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx2_play"),
        displayName = "ARMSX2 (Play Store)",
        packageName = "come.nanodata.armsx2",
        platforms = setOf(PS2),
        recipe =
          LaunchRecipe(
            activity = "kr.co.iefriends.pcsx2.activities.MainActivity",
            method = LaunchMethod.Shell,
          ),
        packagePatterns = listOf("come.nanodata.armsx2.*"),
        downloadUrl = "https://github.com/ARMSX2/ARMSX2/releases",
      ),
      // A native port of the Jak and Daxter games, which keeps their saves on a PS2 folder card.
      KnownEmulator(
        id = EmulatorId("pocketgoal"),
        displayName = "PocketGOAL",
        packageName = "dev.sargunv.pocketgoal",
        platforms = setOf(PS2),
        recipe = LaunchRecipe.ViewRom,
        ranked = false,
      ),
      KnownEmulator(
        id = EmulatorId("psx2"),
        displayName = "PSX2",
        packageName = "com.izzy2lost.psx2",
        platforms = setOf(PS2),
        recipe = LaunchRecipe.ViewRom,
        packagePatterns = listOf("com.izzy2lost.*"),
        downloadUrl = "https://github.com/izzy2lost/PSX2/releases",
        releases = gitHub("izzy2lost/PSX2"),
      ),
      KnownEmulator(
        id = EmulatorId("pcsx2"),
        displayName = "PCSX2",
        packageName = "net.pcsx2.emulator",
        platforms = setOf(PS2),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = "https://github.com/PCSX2/pcsx2/releases",
        releases = gitHub("PCSX2/pcsx2"),
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp"),
        displayName = "PPSSPP",
        packageName = "org.ppsspp.ppsspp",
        platforms = setOf(PSP),
        recipe = ppsspp,
        packagePatterns = listOf("org.ppsspp.*"),
        downloadUrl = playStore("org.ppsspp.ppsspp"),
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp_gold"),
        displayName = "PPSSPP Gold",
        packageName = "org.ppsspp.ppssppgold",
        platforms = setOf(PSP),
        recipe = ppsspp,
        downloadUrl = playStore("org.ppsspp.ppssppgold"),
      ),
      KnownEmulator(
        id = EmulatorId("ppsspp_legacy"),
        displayName = "PPSSPP Legacy",
        packageName = "org.ppsspp.ppsspplegacy",
        platforms = setOf(PSP),
        recipe = ppsspp,
        downloadUrl = "https://www.ppsspp.org/docs/reference/legacy-edition/",
      ),
      KnownEmulator(
        id = EmulatorId("vita3k_zx"),
        displayName = "Vita3K ZX",
        packageName = "org.vita3k.emulator.ikhoeyZX",
        platforms = setOf(VITA),
        recipe = vita3k,
        downloadUrl = "https://github.com/ikhoeyZX/Vita3K-Android/releases",
        releases = gitHub("ikhoeyZX/Vita3K-Android"),
      ),
      KnownEmulator(
        id = EmulatorId("vita3k"),
        displayName = "Vita3K",
        packageName = "org.vita3k.emulator",
        platforms = setOf(VITA),
        recipe = vita3k,
        packagePatterns = listOf("org.vita3k.emulator*"),
        downloadUrl = "https://github.com/Vita3K/Vita3K-Android/releases",
        releases = gitHub("Vita3K/Vita3K-Android"),
      ),
      KnownEmulator(
        id = EmulatorId("vita3k_plus"),
        displayName = "Vita3K+",
        packageName = "org.vita3kplus.emulator",
        platforms = setOf(VITA),
        recipe = vita3k,
        downloadUrl = "https://github.com/nckstwrt/Vita3K-Plus/releases",
        releases = gitHub("nckstwrt/Vita3K-Plus"),
      ),
      // Android 13 and later reject explicit-activity launches of some Redream builds:
      // https://github.com/TapiocaFox/Daijishou/issues/487
      KnownEmulator(
        id = EmulatorId("redream"),
        displayName = "Redream",
        packageName = "io.recompiled.redream",
        platforms = setOf(DREAMCAST),
        recipe = LaunchRecipe(activity = "io.recompiled.redream.MainActivity"),
        downloadUrl = playStore("io.recompiled.redream"),
      ),
      KnownEmulator(
        id = EmulatorId("flycast"),
        displayName = "Flycast",
        packageName = "com.flycast.emulator",
        platforms = setOf(DREAMCAST, ARCADE),
        recipe =
          LaunchRecipe(
            activity = "com.flycast.emulator.MainActivity",
            // Flycast opens the URI's string as a path unless it's a content URI.
            data = RomData.Path,
            method = LaunchMethod.Shell,
          ),
        downloadUrl = playStore("com.flycast.emulator"),
      ),
      KnownEmulator(
        id = EmulatorId("yabasanshiro"),
        displayName = "Yaba Sanshiro 2",
        packageName = "org.devmiyax.yabasanshioro2",
        platforms = setOf(SATURN),
        recipe = yabaSanshiro,
        downloadUrl = playStore("org.devmiyax.yabasanshioro2"),
      ),
      KnownEmulator(
        id = EmulatorId("yabasanshiro_pro"),
        displayName = "Yaba Sanshiro 2 Pro",
        packageName = "org.devmiyax.yabasanshioro2.pro",
        platforms = setOf(SATURN),
        recipe = yabaSanshiro,
      ),
      KnownEmulator(
        id = EmulatorId("saturn_emu"),
        displayName = "Saturn.emu",
        packageName = "com.explusalpha.SaturnEmu",
        platforms = setOf(SATURN),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("com.explusalpha.SaturnEmu"),
      ),
      KnownEmulator(
        id = EmulatorId("md_emu"),
        displayName = "MD.emu",
        packageName = "com.explusalpha.MdEmu",
        platforms = setOf(GENESIS, MASTER_SYSTEM, GAME_GEAR, SEGA_CD, SEGA_32X),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("com.explusalpha.MdEmu"),
      ),
      KnownEmulator(
        id = EmulatorId("mame4droid"),
        displayName = "MAME4droid",
        packageName = "com.seleuco.mame4droid",
        platforms = setOf(ARCADE),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("com.seleuco.mame4droid"),
      ),
      KnownEmulator(
        id = EmulatorId("fbalpha"),
        displayName = "FinalBurn Alpha",
        packageName = "com.bangkokfusion.finalburn",
        platforms = setOf(ARCADE),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = playStore("com.bangkokfusion.finalburn"),
      ),
      KnownEmulator(
        id = EmulatorId("hakux"),
        displayName = "hakuX",
        packageName = "com.rfandango.haku_x",
        platforms = setOf(XBOX),
        recipe = LaunchRecipe.ViewRom,
        downloadUrl = "https://github.com/rfandango/hakuX/releases",
        releases = gitHub("rfandango/hakuX"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx3"),
        displayName = "ARMSX3",
        packageName = "com.armsx3",
        platforms = setOf(PS3),
        recipe = armsx3,
        downloadUrl = "https://github.com/ARMSX2/ARMSX3/releases",
        releases = gitHub("ARMSX2/ARMSX3"),
      ),
      KnownEmulator(
        id = EmulatorId("armsx3_play"),
        displayName = "ARMSX3 (Play Store)",
        packageName = "com.armsx3.play",
        platforms = setOf(PS3),
        recipe = armsx3,
        downloadUrl = playStore("com.armsx3.play"),
      ),
      KnownEmulator(
        id = EmulatorId("aps3e"),
        displayName = "aPS3e",
        packageName = "aenu.aps3e",
        platforms = setOf(PS3),
        recipe =
          LaunchRecipe.withExtras(
            action = "aenu.intent.action.APS3E",
            activity = "aenu.aps3e.EmulatorActivity",
            extras = mapOf("iso_uri" to ExtraValue.RomUriString),
          ),
        downloadUrl = playStore("aenu.aps3e"),
      ),
      KnownEmulator(
        id = EmulatorId("phobos"),
        displayName = "Phobos",
        packageName = "com.phobos.emulator",
        platforms = phobosPlatforms.keys,
        recipe =
          LaunchRecipe(
            activity = "com.phobos.emulator.MainActivity",
            extras = mapOf("platform" to ExtraValue.PlatformName(phobosPlatforms)),
          ),
        downloadUrl = "https://github.com/pwnedbygary/phobos/releases",
        releases = gitHub("pwnedbygary/phobos"),
      ),
    )

  private val retroArchIds =
    listOf(EmulatorId("retroarch"), EmulatorId("retroarch_64"), EmulatorId("retroarch_32"))

  private fun ids(vararg ids: String) = ids.map(::EmulatorId)

  /**
   * Every known emulator for each platform, best first: the order the fallback rule tries them in
   * when nothing names an emulator.
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
