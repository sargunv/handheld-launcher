package dev.sargunv.handheld.launch

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchCommandBuilder
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ShellLaunchTest {
  private fun command(emulator: String, platform: PlatformSlug, rom: String): LaunchCommand {
    val known = EmulatorRegistry[EmulatorId(emulator)]!!
    val build =
      LaunchCommandBuilder.build(known.recipe, known.packageName, LaunchInput(rom, platform))
    return assertIs<LaunchBuild.Ready>(build).command
  }

  @Test
  fun draStic() {
    val command = command("drastic", PlatformCatalog.NDS, "/storage/emulated/0/ROMs/game.nds")
    assertEquals(LaunchMethod.Shell, command.method)
    assertEquals(
      listOf(
        "/system/bin/am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-n",
        "com.dsemu.drastic/com.dsemu.drastic.DraSticActivity",
        "-d",
        "file:///storage/emulated/0/ROMs/game.nds",
        "-f",
        "0x44008000",
      ),
      command.amStartArgv(),
    )
  }

  @Test
  fun flycastSendsAType() {
    val command = command("flycast", PlatformCatalog.DREAMCAST, "/storage/emulated/0/ROMs/game.chd")
    assertEquals(
      listOf(
        "/system/bin/am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-n",
        "com.flycast.emulator/com.flycast.emulator.MainActivity",
        "-c",
        "android.intent.category.DEFAULT",
        "-d",
        "/storage/emulated/0/ROMs/game.chd",
        "-t",
        "application/octet-stream",
        "-f",
        "0x10008000",
      ),
      command.amStartArgv(),
    )
  }

  @Test
  fun awkwardFileNamesReachTheCommandIntact() {
    val rom = "/storage/emulated/0/ROMs/Pokémon #1 (100%)?.nds"
    fun dataOf(command: LaunchCommand) = command.amStartArgv().let { it[it.indexOf("-d") + 1] }

    assertEquals(
      "file:///storage/emulated/0/ROMs/Pok%C3%A9mon%20%231%20(100%25)%3F.nds",
      dataOf(command("drastic", PlatformCatalog.NDS, rom)),
    )
    assertEquals(rom, dataOf(command("flycast", PlatformCatalog.DREAMCAST, rom)))
  }

  @Test
  fun extrasAndPackageTargets() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        action = IntentAction.MAIN,
        extras =
          listOf(
            LaunchExtra.StringValue("path", "/roms/a b.iso"),
            LaunchExtra.BooleanValue("resume", false),
            LaunchExtra.StringArray("args", listOf("-r", "a,b")),
          ),
      )
    assertEquals(
      listOf(
        "/system/bin/am",
        "start",
        "-a",
        "android.intent.action.MAIN",
        "-p",
        "dev.example.emu",
        "--es",
        "path",
        "/roms/a b.iso",
        "--ez",
        "resume",
        "false",
        "--esa",
        "args",
        "-r,a\\,b",
      ),
      command.amStartArgv(),
    )
  }

  @Test
  fun contentUrisCantGoThroughTheShell() {
    val command = command("armsx2", PlatformCatalog.PS2, "/storage/emulated/0/ROMs/game.iso")
    assertEquals(LaunchMethod.Shell, command.method)
    assertFailsWith<IllegalArgumentException> { command.amStartArgv() }
  }
}
