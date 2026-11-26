package dev.sargunv.handheld.domain.storage

import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameLayoutTest {
  @Test
  fun placesGamesByPlatformFolder() {
    assertEquals(
      "/storage/emulated/0/ROMs/ps2",
      GameLayout.platformFolder("/storage/emulated/0/", "ps2"),
    )
    assertEquals("Game.chd", GameLayout.romName("Game", listOf("Game.chd")))
    assertEquals(
      "Game",
      GameLayout.romName("Game", listOf("Game (Disc 1).chd", "Game (Disc 2).chd")),
    )
  }

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
      GameLayout.playlist(PlatformSlug("ps1"), listOf("Game (Disc 2).chd", "Game (Disc 1).chd")),
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
}
