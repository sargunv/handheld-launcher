package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.KnownEmulator
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchCommandBuilderTest {
  private val rom = "/storage/emulated/0/ROMs/game.iso"

  // A file name with every character that a URI treats specially.
  private val awkwardRom = "/storage/emulated/0/ROMs/Pokémon #1 (100%)?.nds"

  private fun emulator(id: String): KnownEmulator = assertNotNull(EmulatorRegistry[EmulatorId(id)])

  private fun build(
    emulator: KnownEmulator,
    input: LaunchInput,
    core: LibretroCore? = LibretroCores.recommendedFor(input.platform),
  ): LaunchCommand =
    assertIs<LaunchBuild.Ready>(
        LaunchCommandBuilder.build(emulator.recipe, emulator.packageName, input, core),
      )
      .command

  private fun build(id: String, platform: PlatformSlug, romPath: String = rom) =
    build(emulator(id), LaunchInput(romPath, platform))

  @Test
  fun viewRomHandsOverAContentUri() {
    val command =
      assertIs<LaunchBuild.Ready>(
          LaunchCommandBuilder.build(
            LaunchRecipe.ViewRom,
            "dev.example.app",
            LaunchInput(rom, PlatformCatalog.PS2),
          ),
        )
        .command
    assertEquals(
      LaunchCommand(
        packageName = "dev.example.app",
        action = IntentAction.VIEW,
        categories = listOf(IntentCategory.DEFAULT),
        data = LaunchUri.ProviderFile(rom),
        mimeType = LaunchRecipe.OCTET_STREAM,
        flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.GrantReadUriPermission),
        clipData = LaunchUri.ProviderFile(rom),
      ),
      command,
    )
    assertEquals(listOf(LaunchUri.ProviderFile(rom)), command.readGrants)
    assertFalse(command.canUseShell)
  }

  @Test
  fun retroArchLoadsTheRecommendedCore() {
    val command = build("retroarch_64", PlatformCatalog.SNES)
    assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", command.activity)
    assertNull(command.data)
    assertEquals(
      listOf(
        LaunchExtra.StringValue("ROM", rom),
        LaunchExtra.StringValue(
          "LIBRETRO",
          "/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so",
        ),
        LaunchExtra.StringValue(
          "CONFIGFILE",
          "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
        ),
        LaunchExtra.StringValue("IME", "com.android.inputmethod.latin/.LatinIME"),
        LaunchExtra.StringValue("DATADIR", "/data/data/com.retroarch.aarch64"),
        LaunchExtra.StringValue("SDCARD", "/storage/emulated/0"),
        LaunchExtra.StringValue(
          "EXTERNAL",
          "/storage/emulated/0/Android/data/com.retroarch.aarch64/files",
        ),
      ),
      command.extras,
    )
    assertEquals(LaunchUri.ProviderFile(rom), command.clipData)
    assertTrue(LaunchFlag.GrantReadUriPermission in command.flags)
  }

  @Test
  fun retroArchUsesTheAndroidLibraryName() {
    val command = build("retroarch", PlatformCatalog.N64)
    assertEquals(
      "/data/data/com.retroarch/cores/mupen64plus_next_gles3_libretro_android.so",
      command.extras.first { it.key == "LIBRETRO" }.let { (it as LaunchExtra.StringValue).value },
    )
  }

  @Test
  fun aCoreRecipeWithoutACoreFails() {
    val retroArch = emulator("retroarch")
    val build =
      LaunchCommandBuilder.build(
        retroArch.recipe,
        retroArch.packageName,
        LaunchInput(rom, PlatformCatalog.PS2),
      )
    assertEquals(LaunchBuild.MissingCore(PlatformCatalog.PS2), build)
  }

  @Test
  fun duckStationTakesAPathFromBuild10000() {
    val duckStation = emulator("duckstation")
    val newBuild =
      build(duckStation, LaunchInput(rom, PlatformCatalog.PSX, installedVersionName = "0.1-10234"))
    assertEquals(LaunchExtra.StringValue("bootPath", rom), newBuild.extras[0])
    assertEquals(LaunchExtra.BooleanValue("resumeState", false), newBuild.extras[1])
    assertTrue(newBuild.readGrants.isEmpty())
    assertEquals(
      setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
      newBuild.flags,
    )

    val oldBuild =
      build(duckStation, LaunchInput(rom, PlatformCatalog.PSX, installedVersionName = "0.1-9000"))
    assertEquals(LaunchExtra.UriString("bootPath", LaunchUri.ProviderFile(rom)), oldBuild.extras[0])
    assertTrue(LaunchFlag.GrantReadUriPermission in oldBuild.flags)

    val unknownBuild = build(duckStation, LaunchInput(rom, PlatformCatalog.PSX))
    assertIs<LaunchExtra.UriString>(unknownBuild.extras[0])
  }

  @Test
  fun playlistsGoByPath() {
    val playlist = "/storage/emulated/0/ROMs/game.m3u"
    val command = build("nethersx2", PlatformCatalog.PS2, playlist)
    assertEquals(listOf(LaunchExtra.StringValue("bootPath", playlist)), command.extras)
    assertNull(command.clipData)
  }

  @Test
  fun draSticGetsAFileUriThroughTheShell() {
    val command = build("drastic", PlatformCatalog.NDS, "/storage/emulated/0/ROMs/game.nds")
    assertEquals(LaunchUri.Raw("file:///storage/emulated/0/ROMs/game.nds"), command.data)
    assertNull(command.mimeType)
    assertEquals(LaunchMethod.Shell, command.method)
    assertTrue(command.canUseShell)
    assertTrue(command.categories.isEmpty())
  }

  @Test
  fun linkBoyUsesItsScheme() {
    val command = build("linkboy", PlatformCatalog.GBA, "/storage/emulated/0/ROMs/game.gba")
    assertEquals(LaunchUri.Raw("linkboy://emulator/storage/emulated/0/ROMs/game.gba"), command.data)
    assertNull(command.activity)
  }

  @Test
  fun schemeUrisEncodeThePath() {
    val command =
      build("linkboy", PlatformCatalog.GB, "/storage/emulated/0/ROMs/Pokémon #1 (100%)?.gb")
    assertEquals(
      LaunchUri.Raw(
        "linkboy://emulator/storage/emulated/0/ROMs/Pok%C3%A9mon%20%231%20(100%25)%3F.gb",
      ),
      command.data,
    )
  }

  @Test
  fun fileUrisEncodeThePath() {
    val command = build("drastic", PlatformCatalog.NDS, awkwardRom)
    assertEquals(
      LaunchUri.Raw("file:///storage/emulated/0/ROMs/Pok%C3%A9mon%20%231%20(100%25)%3F.nds"),
      command.data,
    )
  }

  @Test
  fun pathsPassAsWritten() {
    val flycast = build("flycast", PlatformCatalog.DREAMCAST, awkwardRom)
    assertEquals(LaunchUri.Raw(awkwardRom), flycast.data)

    val seedless = build("seedlessds", PlatformCatalog.NDS, awkwardRom)
    assertEquals(listOf(LaunchExtra.StringValue("rom_path", awkwardRom)), seedless.extras)
  }

  @Test
  fun melonDsTakesTheUriTwice() {
    val command = build("melonds", PlatformCatalog.NDS)
    val uri = LaunchUri.ProviderFile(rom)
    assertEquals("me.magnum.melonds.LAUNCH_ROM", command.action)
    assertEquals(uri, command.data)
    assertNull(command.mimeType)
    assertEquals(listOf(LaunchExtra.UriValue("uri", uri)), command.extras)
    assertEquals(listOf(uri), command.readGrants)
  }

  @Test
  fun vita3kStartsTheTitle() {
    val command =
      build("vita3k", PlatformCatalog.VITA, "/storage/emulated/0/ROMs/Persona 4 [PCSE00120].zip")
    assertEquals(
      listOf(LaunchExtra.StringArray("AppStartParameters", listOf("-r", "PCSE00120"))),
      command.extras,
    )

    val noTitle = build("vita3k", PlatformCatalog.VITA, "/storage/emulated/0/ROMs/game.zip")
    assertTrue(noTitle.extras.isEmpty())
  }

  @Test
  fun vitaTitleIds() {
    assertEquals("PCSE00120", VitaTitleId.fromFileName("Persona 4 [PCSE00120].vpk"))
    assertEquals("PCSB00245", VitaTitleId.fromFileName("PCSB00245 - Gravity Rush.zip"))
    assertNull(VitaTitleId.fromFileName("Gravity Rush.zip"))
  }

  @Test
  fun phobosGetsItsPlatformName() {
    assertEquals(
      LaunchExtra.StringValue("platform", "scd"),
      build("phobos", PlatformCatalog.SEGA_CD).extras.single(),
    )
    assertEquals(
      LaunchExtra.StringValue("platform", "nes"),
      build("phobos", PlatformCatalog.FAMICOM).extras.single(),
    )
  }

  @Test
  fun flycastPassesAPathWithAType() {
    val command = build("flycast", PlatformCatalog.DREAMCAST, "/storage/emulated/0/ROMs/game.chd")
    assertEquals(LaunchUri.Raw("/storage/emulated/0/ROMs/game.chd"), command.data)
    assertEquals(LaunchRecipe.OCTET_STREAM, command.mimeType)
    assertTrue(command.canUseShell)
    assertFalse(LaunchFlag.GrantReadUriPermission in command.flags)
  }
}
