package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaveFilesTest {
  private val storage = "/storage/emulated/0"

  private fun location(id: String, platform: PlatformSlug) =
    assertNotNull(SaveLocations.forEmulator(EmulatorId(id), platform))

  @Test
  fun romNamedFilesTryEachFolderAndExtension() {
    val melonDs = location("melonds", PlatformCatalog.NDS)
    assertEquals(
      listOf(
        "$storage/Android/data/me.magnum.melonds/files/saves/Pokemon Black.sav",
        "$storage/melonDS/saves/Pokemon Black.sav",
      ),
      SaveFiles.candidates(
        melonDs,
        "$storage/ROMs/nds/Pokemon Black.nds",
        SaveRoots(storage, packageName = "me.magnum.melonds"),
      ),
    )
  }

  @Test
  fun retroArchFillsInTheCoreFolderOrSkipsIt() {
    val retroArch = location("retroarch", PlatformCatalog.SNES)
    val rom = "$storage/ROMs/snes/Chrono Trigger.sfc"
    val withCore =
      assertNotNull(
        SaveFiles.candidates(
          retroArch,
          rom,
          SaveRoots(storage, packageName = "com.retroarch", core = "snes9x_libretro"),
        ),
      )
    assertEquals("$storage/RetroArch/saves/Snes9x/Chrono Trigger.srm", withCore.first())
    val withoutCore =
      assertNotNull(SaveFiles.candidates(retroArch, rom, SaveRoots(storage, packageName = "x")))
    assertEquals("$storage/RetroArch/saves/Chrono Trigger.srm", withoutCore.first())
  }

  @Test
  fun besideRomUsesTheRomsFolder() {
    val seedless = location("seedlessds", PlatformCatalog.NDS)
    assertEquals(
      listOf("/sd/ROMs/nds/game.dsv", "/sd/ROMs/nds/game.sav"),
      SaveFiles.candidates(
        seedless,
        "/sd/ROMs/nds/game.nds",
        SaveRoots(storage, packageName = "x"),
      ),
    )
  }

  @Test
  fun otherLayoutsHaveNoSingleFile() {
    val roots = SaveRoots(storage, packageName = "x")
    assertNull(SaveFiles.candidates(location("armsx2", PlatformCatalog.PS2), "/a.chd", roots))
    assertNull(SaveFiles.candidates(location("ppsspp", PlatformCatalog.PSP), "/a.iso", roots))
    assertNull(SaveFiles.candidates(location("linkboy", PlatformCatalog.GBA), "/a.gba", roots))
  }
}
