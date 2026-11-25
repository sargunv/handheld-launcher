package dev.sargunv.handheld.domain.launch.android

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchCommandBuilder
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
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
        "/storage/emulated/0/ROMs/game.nds",
        "-f",
        "0x44008000",
      ),
      ShellLaunch.argv(command),
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
      ShellLaunch.argv(command),
    )
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
      ShellLaunch.argv(command),
    )
  }

  @Test
  fun commandLinesQuoteEveryArgument() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        action = IntentAction.MAIN,
        extras = listOf(LaunchExtra.StringValue("path", "/roms/Link's Awakening.gb")),
        flags = setOf(LaunchFlag.NewTask),
      )
    assertEquals(
      "'/system/bin/am' 'start' '-a' 'android.intent.action.MAIN' '-p' 'dev.example.emu' " +
        "'-f' '0x10000000' '--es' 'path' '/roms/Link'\\''s Awakening.gb'",
      ShellLaunch.commandLine(command),
    )
  }

  @Test
  fun contentUrisCantGoThroughTheShell() {
    val command = command("armsx2", PlatformCatalog.PS2, "/storage/emulated/0/ROMs/game.iso")
    assertEquals(LaunchMethod.Shell, command.method)
    assertFailsWith<IllegalArgumentException> { ShellLaunch.argv(command) }
  }
}
