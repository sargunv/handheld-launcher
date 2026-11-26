package dev.sargunv.handheld.domain.saves

import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInSavesTest {
  private val saves = BuiltInSaves("/data/files/saves/")

  @Test
  fun eachGameHasItsOwnFolder() {
    assertEquals("/data/files/saves/42", saves.folderOf(42))
    assertEquals("/data/files/saves/7", saves.folderOf(7))
  }

  @Test
  fun saveMemoryIsNamedAfterTheGamesFile() {
    assertEquals(
      "/data/files/saves/42/Tetris (USA).srm",
      saves.saveRamOf(42, "/storage/emulated/0/ROMs/nes/Tetris (USA).nes"),
    )
    assertEquals(
      "/data/files/saves/42/Pokemon v1.2.srm",
      saves.saveRamOf(42, "/ROMs/gba/Pokemon v1.2.gba"),
    )
    assertEquals("/data/files/saves/42/.hidden.srm", saves.saveRamOf(42, "/ROMs/.hidden"))
  }

  @Test
  fun eachGameHasSixStateSlotsNamedAfterItsFile() {
    val states = saves.statesOf(42, "/ROMs/gba/Pokemon Gaia.gba")
    assertEquals(
      (1..6).map { "/data/files/saves/42/Pokemon Gaia.state$it" },
      states,
    )
    assertEquals(
      "/data/files/saves/42/Pokemon Gaia.state1.png",
      BuiltInSaves.thumbnailOf(states.first()),
    )
  }

  @Test
  fun savesAreFiledUnderTheCoreApartFromStandaloneEmulators() {
    assertEquals("builtin-mgba_libretro", BuiltInSaves.emulatorOf("mgba_libretro"))
  }
}
