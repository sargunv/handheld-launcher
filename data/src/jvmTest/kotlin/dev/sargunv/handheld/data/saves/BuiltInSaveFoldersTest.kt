package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class BuiltInSaveFoldersTest {
  private val dir = createTempDirectory("built-in-saves").toFile()
  private val saves = BuiltInSaves(File(dir, "saves").path)
  private val history = File(dir, "save-history")
  private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
  private val folders =
    BuiltInSaveFolders(
      saves,
      history,
      object : Clock {
        override fun now() = now
      },
    )

  private val romPath = "/storage/emulated/0/ROMs/gbc/uCity.gbc"
  private val adoption = Adoption(GameKey.Local(romPath), GameKey.Romm(7))
  private val local = File(saves.folderOf(adoption.local))
  private val own = File(saves.folderOf(adoption.rom))

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  @Test
  fun aLocalGamesSavesBecomeTheRomsWhenItHasNone() = runTest {
    File(local, "uCity.srm").apply { parentFile.mkdirs() }.writeText("local save")
    File(local, "uCity.state1").writeText("state")
    // An empty folder, as a canceled launch leaves, holds no saves.
    own.mkdirs()

    folders.adopt(listOf(adoption))

    assertEquals("local save", File(own, "uCity.srm").readText())
    assertEquals("state", File(own, "uCity.state1").readText())
    assertFalse(local.exists())
    // The folders that named the file's path go too.
    assertFalse(File(saves.localFolderOf("/storage")).exists())
    assertEquals(emptyList(), keptSaves(history, 7))
  }

  @Test
  fun whenTheRomHasSavesTheLocalOnesGoToItsHistory() = runTest {
    File(local, "uCity.srm").apply { parentFile.mkdirs() }.writeText("local save")
    File(local, "core/extra.sav").apply { parentFile.mkdirs() }.writeText("extra")
    File(own, "uCity.srm").apply { parentFile.mkdirs() }.writeText("rom save")

    folders.adopt(listOf(adoption))
    // Again, as the next scan does, changes nothing.
    folders.adopt(listOf(adoption))

    assertEquals("rom save", File(own, "uCity.srm").readText())
    assertEquals(listOf("uCity.srm"), own.list()!!.toList())
    assertFalse(local.exists())
    val kept = keptSaves(history, 7)
    assertEquals(2, kept.size)
    assertTrue(
      kept.all { it.copy == SaveCopy.Device && it.keptAt == now && it.emulator == "builtin" },
    )
    assertEquals(
      setOf("local save", "extra"),
      kept.mapTo(mutableSetOf()) { File(it.path).readText() },
    )
    assertEquals(
      setOf(
        "${now.toEpochMilliseconds()}-device-uCity.srm",
        "${now.toEpochMilliseconds()}-device-core_extra.sav",
      ),
      kept.mapTo(mutableSetOf()) { File(it.path).name },
    )
  }

  @Test
  fun aLocalGameWithoutSavesChangesNothing() = runTest {
    File(own, "uCity.srm").apply { parentFile.mkdirs() }.writeText("rom save")
    folders.adopt(listOf(adoption))
    assertEquals("rom save", File(own, "uCity.srm").readText())
    assertFalse(history.exists())
  }
}
