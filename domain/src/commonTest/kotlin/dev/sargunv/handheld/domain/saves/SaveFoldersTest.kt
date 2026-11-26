package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaveFoldersTest {
  private fun location(id: String, platform: PlatformSlug) =
    assertNotNull(SaveLocations.forEmulator(EmulatorId(id), platform))

  @Test
  fun ps2MemoryCardsDontSyncPerGame() {
    val armsx2 = location("armsx2", PlatformCatalog.PS2)
    assertNull(SaveFolders.idKind(armsx2, PlatformCatalog.PS2))
    assertNull(SaveFolders.forGame(armsx2, PlatformCatalog.PS2, "SLUS20152"))
    assertNull(
      SaveFiles.candidates(armsx2, "/roms/Jak II.chd", SaveRoots("/s", packageName = "com.armsx2")),
    )
  }

  @Test
  fun theThorsEmulatorsFileSavesByTheGamesId() {
    val psp = location("ppsspp", PlatformCatalog.PSP)
    assertEquals(GameIdKind.Psp, SaveFolders.idKind(psp, PlatformCatalog.PSP))
    assertEquals(
      FolderSave(listOf(SaveArea("", "", SaveSelection.Prefixed("ULUS10064"))), "ULUS10064.zip"),
      SaveFolders.forGame(psp, PlatformCatalog.PSP, "ULUS10064"),
    )
    assertNull(SaveFolders.forGame(psp, PlatformCatalog.PSP, "not a serial"))

    val azahar =
      assertNotNull(
        SaveFolders.forGame(
          location("azahar", PlatformCatalog.N3DS),
          PlatformCatalog.N3DS,
          "0004000000033500",
        ),
      )
    assertEquals(
      listOf(
        "00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/00033500/data",
        "00000000000000000000000000000000/00000000000000000000000000000000/extdata/00000000/00000335",
      ),
      azahar.areas.map { it.folder },
    )

    val eden =
      assertNotNull(
        SaveFolders.forGame(
          location("eden", PlatformCatalog.SWITCH),
          PlatformCatalog.SWITCH,
          "0100abcd12345000",
        ),
      )
    assertEquals(
      listOf("0100ABCD12345000/", "device/0100ABCD12345000/"),
      eden.areas.map { it.zipPrefix },
    )

    val gameCube =
      assertNotNull(
        SaveFolders.forGame(
          location("dolphin", PlatformCatalog.GAMECUBE),
          PlatformCatalog.GAMECUBE,
          "GALP",
        ),
      )
    assertEquals("EUR/Card A", gameCube.areas.single().folder)
    val wii =
      assertNotNull(
        SaveFolders.forGame(location("dolphin", PlatformCatalog.WII), PlatformCatalog.WII, "RSBE"),
      )
    assertEquals("52534245/data", wii.areas.single().folder)
  }

  @Test
  fun gciFilesAreTheGamesByTheirName() {
    val gci = SaveSelection.GciFiles("GZLE")
    assertEquals(true, gci.claims("01-GZLE-gczelda.gci"))
    assertEquals(false, gci.claims("01-GZLJ-gczelda.gci"))
    assertEquals(false, gci.claims("01-GZLE-gczelda.sav"))
  }
}
