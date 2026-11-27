package dev.sargunv.handheld.launch

import android.content.Intent
import android.net.Uri
import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.IntentCategory
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchUri
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LaunchIntentsTest {
  private val rom = "/storage/emulated/0/ROMs/game.iso"
  private val uriFor = { path: String -> Uri.parse("content://test.fileprovider/root$path") }
  private val romUri = Uri.parse("content://test.fileprovider/root$rom")

  @Test
  fun contentUrisAreGrantedToThePackage() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        action = IntentAction.VIEW,
        categories = listOf(IntentCategory.DEFAULT),
        data = LaunchUri.ProviderFile(rom),
        mimeType = "application/octet-stream",
        flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask),
        clipData = LaunchUri.ProviderFile(rom),
      )
    val intent = command.toIntent(uriFor)

    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals("dev.example.emu", intent.`package`)
    assertNull(intent.component)
    assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories)
    assertEquals(romUri, intent.data)
    assertEquals("application/octet-stream", intent.type)
    assertEquals(romUri, intent.clipData?.getItemAt(0)?.uri)
    assertEquals(
      Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      intent.flags,
    )
  }

  @Test
  fun everyExtraKeepsItsType() {
    val command =
      LaunchCommand(
        packageName = "dev.example.emu",
        activity = "dev.example.emu.Play",
        action = IntentAction.MAIN,
        extras =
          listOf(
            LaunchExtra.StringValue("path", rom),
            LaunchExtra.UriValue("uri", LaunchUri.ProviderFile(rom)),
            LaunchExtra.UriString("uriString", LaunchUri.ProviderFile(rom)),
            LaunchExtra.BooleanValue("resume", false),
            LaunchExtra.StringArray("args", listOf("-r", "PCSE00120")),
          ),
      )
    val intent = command.toIntent(uriFor)

    assertEquals("dev.example.emu/dev.example.emu.Play", intent.component?.flattenToString())
    assertNull(intent.data)
    assertNull(intent.categories)
    assertEquals(rom, intent.getStringExtra("path"))
    assertEquals(romUri, intent.getParcelableExtra("uri", Uri::class.java))
    assertEquals(romUri.toString(), intent.getStringExtra("uriString"))
    assertEquals(false, intent.getBooleanExtra("resume", true))
    assertContentEquals(arrayOf("-r", "PCSE00120"), intent.getStringArrayExtra("args"))
  }

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
        data = LaunchUri.ProviderFile(rom),
      )
    assertFailsWith<IllegalArgumentException> { command.amStartArgv() }
  }
}
