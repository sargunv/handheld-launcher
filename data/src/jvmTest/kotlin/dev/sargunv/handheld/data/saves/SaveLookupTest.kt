package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveLookupTest {
  private val storage = createTempDirectory("save-lookup").toFile()

  @AfterTest fun cleanUp() = check(storage.deleteRecursively())

  private fun lookUp(
    emulator: String,
    platform: PlatformSlug,
    romPath: String,
    gameId: (file: String, keys: String?) -> String? = { _, _ -> null },
  ): SaveLookup {
    val known = checkNotNull(EmulatorRegistry[EmulatorId(emulator)])
    val target = LaunchTarget(known.packageName, known.recipe, known)
    return gameSaveFor(target, platform, 7, romPath, listOf(storage.path), gameId)
  }

  @Test
  fun aFilePerGameNeedsNoGameId() {
    val lookup = lookUp("drastic", PlatformCatalog.NDS, "/roms/nds/Game.nds")
    val unit = assertIs<FileUnit>(assertIs<SaveLookup.Found>(lookup).save.unit)
    assertEquals("Game.dsv", unit.candidates.first().name)
  }

  @Test
  fun foldersAreFiledUnderTheIdReadWithTheEmulatorsKeys() {
    val keys = File(storage, "Android/data/dev.eden.eden_emulator/files/keys/prod.keys")
    keys.parentFile.mkdirs()
    keys.writeText("")
    var read: Pair<String, String?>? = null
    val lookup =
      lookUp("eden", PlatformCatalog.SWITCH, "/roms/switch/Game.nsp") { file, keys ->
        read = file to keys
        "0100000000010000"
      }
    assertEquals("/roms/switch/Game.nsp" to keys.path, read)
    val unit = assertIs<FolderUnit>(assertIs<SaveLookup.Found>(lookup).save.unit)
    assertEquals("0100000000010000.zip", unit.save.name)
  }

  @Test
  fun aPs2GameSyncsItsOwnCardSharedByEveryPs2CardEmulator() {
    val lookup =
      lookUp("nethersx2", PlatformCatalog.PS2, "/roms/ps2/Game.iso") { _, _ -> "SLUS_123.45" }
    val save = assertIs<SaveLookup.Found>(lookup).save
    val cards = assertIs<Ps2CardUnit>(save.unit)
    assertEquals("SLUS_123.45" to "Game.ps2", cards.serial to cards.name)
    assertTrue("pcsx2" in save.sharesWith)
  }

  @Test
  fun anUnreadableIdMeansNoGameId() {
    assertEquals(SaveLookup.NoGameId, lookUp("ppsspp", PlatformCatalog.PSP, "/roms/psp/Game.iso"))
  }
}
