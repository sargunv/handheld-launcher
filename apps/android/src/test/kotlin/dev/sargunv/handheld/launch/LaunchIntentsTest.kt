package dev.sargunv.handheld.launch

import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.IntentCategory
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LaunchIntentsTest {
  @Test
  fun amStartArgvCarriesTheCommand() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        activity = "dev.example.emu.Play",
        action = IntentAction.VIEW,
        categories = listOf(IntentCategory.DEFAULT),
        data = LaunchUri.Raw("/roms/a b.iso"),
        mimeType = "application/octet-stream",
        extras =
          listOf(
            LaunchExtra.StringValue("path", "/roms/a b.iso"),
            LaunchExtra.BooleanValue("resume", false),
            LaunchExtra.StringArray("args", listOf("-r", "a,b")),
          ),
        flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
      )
    assertEquals(
      listOf(
        "/system/bin/am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-n",
        "dev.example.emu/dev.example.emu.Play",
        "-c",
        "android.intent.category.DEFAULT",
        "-d",
        "/roms/a b.iso",
        "-t",
        "application/octet-stream",
        "-f",
        "0x50008000",
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
    assertEquals(
      listOf(
        "/system/bin/am",
        "start",
        "-a",
        "android.intent.action.MAIN",
        "-p",
        "dev.example.emu",
      ),
      LaunchCommand("dev.example.emu", action = IntentAction.MAIN).amStartArgv(),
    )
  }

  @Test
  fun amStartArgvRefusesContentUris() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        action = IntentAction.VIEW,
        data = LaunchUri.ProviderFile("/storage/emulated/0/ROMs/game.iso"),
      )
    assertFailsWith<IllegalArgumentException> { command.amStartArgv() }
  }
}
