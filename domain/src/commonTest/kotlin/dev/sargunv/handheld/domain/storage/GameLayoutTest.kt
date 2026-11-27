package dev.sargunv.handheld.domain.storage

import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class GameLayoutTest {
  @Test
  fun placesGamesByPlatformFolder() {
    assertEquals(
      "/storage/emulated/0/ROMs/ps2",
      GameLayout.platformFolder("/storage/emulated/0/", "ps2"),
    )
  }

  @Test
  fun namesARomByItsFileOrItsFolder() {
    assertEquals("Game.chd", GameLayout.romName(rom("Game", "Game.chd")))
    assertEquals(
      "Game",
      GameLayout.romName(rom("Game", "Game (Disc 1).chd", "Game (Disc 2).chd")),
    )
    assertNull(GameLayout.romName(rom("..", "a.chd", "b.chd")))
    assertNull(GameLayout.romName(rom("Game", "sub/Game.chd")))
  }

  private fun rom(fileName: String, vararg files: String) =
    Rom(
      id = 1,
      platformId = 1,
      platformSlug = "psx",
      title = fileName,
      sortKey = fileName,
      alternativeNames = emptyList(),
      fileName = fileName,
      sizeBytes = 0,
      summary = null,
      genres = emptyList(),
      regions = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      hasMultipleFiles = files.size > 1,
      files = files.mapIndexed { i, name -> RomFile(i.toLong(), name, 0, null) },
      updatedAt = Instant.fromEpochSeconds(0),
      createdAt = Instant.fromEpochSeconds(0),
    )

  @Test
  fun listsDiscsInOrder() {
    val files =
      listOf(
        "Game (Disc 10).cue",
        "Game (Disc 2).cue",
        "Game (Disc 1).cue",
        "Game (Disc 1).bin",
        "Game (Disc 2).bin",
        "Game (Disc 10).bin",
      )
    assertEquals(
      listOf("Game (Disc 1).cue", "Game (Disc 2).cue", "Game (Disc 10).cue"),
      GameLayout.playlist(PlatformSlug("psx"), files),
    )
    assertEquals(
      listOf("Game (Disc 1).chd", "Game (Disc 2).chd"),
      GameLayout.playlist(PlatformSlug("psx"), listOf("Game (Disc 2).chd", "Game (Disc 1).chd")),
    )
  }

  @Test
  fun needsNoPlaylistForOneDiscItsOwnOrAPlatformWithout() {
    assertNull(GameLayout.playlist(PlatformSlug("psx"), listOf("Game.cue", "Game.bin")))
    assertNull(
      GameLayout.playlist(
        PlatformSlug("psx"),
        listOf("Game (Disc 1).chd", "Game (Disc 2).chd", "Game.m3u"),
      ),
    )
    assertNull(
      GameLayout.playlist(PlatformSlug("ps2"), listOf("Game (Disc 1).chd", "Game (Disc 2).chd")),
    )
  }

  @Test
  fun launchesAFolderByItsPlaylistElseItsFirstDisc() {
    assertEquals(
      "Game.m3u",
      GameLayout.launchFile(listOf("Game (Disc 1).cue", "Game.m3u", "Game (Disc 1).bin")),
    )
    assertEquals(
      "Game (Disc 1).cue",
      GameLayout.launchFile(
        listOf("Game (Disc 2).bin", "Game (Disc 2).cue", "Game (Disc 1).bin", "Game (Disc 1).cue"),
      ),
    )
    assertEquals(
      "Game (Disc 1).chd",
      GameLayout.launchFile(listOf("Game (Disc 2).chd", "Game (Disc 1).chd")),
    )
    assertEquals("a.bin", GameLayout.launchFile(listOf("readme.txt", "a.bin")))
    assertEquals("data.pkg", GameLayout.launchFile(listOf("data.pkg", "manual.pdf")))
    assertNull(GameLayout.launchFile(emptyList()))
    // Discs in subfolders keep their paths.
    assertEquals(
      "Disc 1/game.iso",
      GameLayout.launchFile(listOf("Disc 2/game.iso", "Disc 1/game.iso")),
    )
  }
}
