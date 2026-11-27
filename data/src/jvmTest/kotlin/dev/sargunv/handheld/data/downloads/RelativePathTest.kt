package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.romm.RommRomFile
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativePathTest {
  private fun file(name: String, path: String, topLevel: Boolean) =
    RommRomFile(
      id = 0,
      fileName = name,
      filePath = path,
      fileSizeBytes = 1,
      isTopLevel = topLevel,
    )

  @Test
  fun keepsSubfoldersWhenNoFileIsTopLevel() {
    val files =
      listOf(
        file("track.bin", "roms/psx/Game/Disc 1", topLevel = false),
        file("track.bin", "roms/psx/Game/Disc 2", topLevel = false),
      )
    assertEquals(
      listOf("Disc 1/track.bin", "Disc 2/track.bin"),
      files.map { relativePath(files, it) },
    )
  }

  @Test
  fun placesFilesBelowTheTopLevelOnesFolder() {
    val files =
      listOf(
        file("Game.cue", "roms/psx/Game", topLevel = true),
        file("Game (Track 1).bin", "roms/psx/Game/tracks", topLevel = false),
      )
    assertEquals(
      listOf("Game.cue", "tracks/Game (Track 1).bin"),
      files.map { relativePath(files, it) },
    )
  }
}
