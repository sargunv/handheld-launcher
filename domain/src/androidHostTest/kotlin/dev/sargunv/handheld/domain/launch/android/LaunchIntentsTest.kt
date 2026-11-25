package dev.sargunv.handheld.domain.launch.android

import android.content.Intent
import android.net.Uri
import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.launch.IntentAction
import dev.sargunv.handheld.domain.launch.IntentCategory
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchCommandBuilder
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchRecipe
import dev.sargunv.handheld.domain.launch.LaunchUri
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LaunchIntentsTest {
  private val rom = "/storage/emulated/0/ROMs/game.iso"
  private val contentUris = ContentUris { Uri.parse("content://test.fileprovider/root$it") }
  private val romUri = Uri.parse("content://test.fileprovider/root$rom")

  private fun command(
    emulator: String,
    platform: dev.sargunv.handheld.domain.platform.PlatformSlug,
  ) =
    EmulatorRegistry[EmulatorId(emulator)]!!.let {
      val build = LaunchCommandBuilder.build(it.recipe, it.packageName, LaunchInput(rom, platform))
      assertIs<LaunchBuild.Ready>(build).command
    }

  @Test
  fun flagBits() {
    assertEquals(0x10000000, LaunchIntents.flagsOf(setOf(LaunchFlag.NewTask)))
    assertEquals(0x00008000, LaunchIntents.flagsOf(setOf(LaunchFlag.ClearTask)))
    assertEquals(0x04000000, LaunchIntents.flagsOf(setOf(LaunchFlag.ClearTop)))
    assertEquals(0x20000000, LaunchIntents.flagsOf(setOf(LaunchFlag.SingleTop)))
    assertEquals(0x40000000, LaunchIntents.flagsOf(setOf(LaunchFlag.NoHistory)))
    assertEquals(0x00000001, LaunchIntents.flagsOf(setOf(LaunchFlag.GrantReadUriPermission)))
    assertEquals(0, LaunchIntents.flagsOf(emptySet()))
  }

  @Test
  fun viewRomTargetsThePackageWithAGrantedContentUri() {
    val command =
      assertIs<LaunchBuild.Ready>(
          LaunchCommandBuilder.build(
            LaunchRecipe.ViewRom,
            "dev.sargunv.pocketgoal",
            LaunchInput(rom, PlatformCatalog.PS2),
          ),
        )
        .command
    val intent = LaunchIntents.toIntent(command, contentUris)

    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals("dev.sargunv.pocketgoal", intent.`package`)
    assertNull(intent.component)
    assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories)
    assertEquals(romUri, intent.data)
    assertEquals(LaunchRecipe.OCTET_STREAM, intent.type)
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
    val intent = LaunchIntents.toIntent(command, contentUris)

    assertEquals("dev.example.emu", intent.component?.packageName)
    assertEquals("dev.example.emu.Play", intent.component?.className)
    assertNull(intent.data)
    assertNull(intent.categories)
    assertEquals(rom, intent.getStringExtra("path"))
    assertEquals(romUri, intent.getParcelableExtra("uri", Uri::class.java))
    assertEquals(romUri.toString(), intent.getStringExtra("uriString"))
    assertEquals(false, intent.getBooleanExtra("resume", true))
    assertContentEquals(arrayOf("-r", "PCSE00120"), intent.getStringArrayExtra("args"))
  }

  @Test
  fun rawUrisPassThrough() {
    val intent = LaunchIntents.toIntent(command("drastic", PlatformCatalog.NDS), contentUris)
    assertEquals(Uri.parse(rom), intent.data)
    assertNull(intent.type)
    assertNull(intent.clipData)
    assertEquals(
      "com.dsemu.drastic/com.dsemu.drastic.DraSticActivity",
      intent.component?.flattenToString(),
    )
  }

  @Test
  fun dolphinGetsItsRomAsAUriExtra() {
    val intent = LaunchIntents.toIntent(command("dolphin", PlatformCatalog.GAMECUBE), contentUris)
    assertEquals(IntentAction.MAIN, intent.action)
    assertEquals(setOf(IntentCategory.DEFAULT), intent.categories)
    assertEquals(romUri, intent.getParcelableExtra("AutoStartFile", Uri::class.java))
    assertEquals(romUri, intent.clipData?.getItemAt(0)?.uri)
    assertEquals(
      Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_NO_HISTORY or
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      intent.flags,
    )
  }
}
