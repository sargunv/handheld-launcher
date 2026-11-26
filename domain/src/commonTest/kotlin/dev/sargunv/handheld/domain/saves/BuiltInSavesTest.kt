package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInSavesTest {
  private val saves = BuiltInSaves("/data/files/saves/")
  private val gba = PlatformSlug("gba")
  private val folder = "/data/files/saves/42"

  @Test
  fun eachGameHasItsOwnFolder() {
    assertEquals("/data/files/saves/42", saves.folderOf(GameKey.Romm(42), gba))
    assertEquals("/data/files/saves/7", saves.folderOf(GameKey.Romm(7), gba))
  }

  @Test
  fun aLocalGamesFolderIsNamedForItsPlatformAndFile() {
    val path = "/storage/emulated/0/ROMs/gba/My Hack.gba"
    assertEquals(
      "/data/files/saves/local/gba/My Hack.gba",
      saves.folderOf(GameKey.Local(path), gba),
    )
    assertEquals(saves.folderOf(GameKey.Local(path), gba), saves.localFolderOf(gba, path))
  }

  @Test
  fun saveMemoryIsNamedAfterTheGamesFile() {
    assertEquals(
      "/data/files/saves/42/Tetris (USA).srm",
      saves.saveRamOf(folder, "/storage/emulated/0/ROMs/nes/Tetris (USA).nes"),
    )
    assertEquals(
      "/data/files/saves/42/Pokemon v1.2.srm",
      saves.saveRamOf(folder, "/ROMs/gba/Pokemon v1.2.gba"),
    )
    assertEquals("/data/files/saves/42/.hidden.srm", saves.saveRamOf(folder, "/ROMs/.hidden"))
  }

  @Test
  fun eachGameHasSixStateSlotsNamedAfterItsFile() {
    val states = saves.statesOf(folder, "/ROMs/gba/Pokemon Gaia.gba")
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
