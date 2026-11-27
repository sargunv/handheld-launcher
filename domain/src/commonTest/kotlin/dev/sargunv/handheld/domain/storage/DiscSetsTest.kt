package dev.sargunv.handheld.domain.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscSetsTest {
  @Test
  fun aCueSheetListsItsFiles() {
    val cue =
      """
      |FILE "Game (Track 1).bin" BINARY
      |  TRACK 01 MODE2/2352
      |    INDEX 01 00:00:00
      |file Game (Track 2).bin BINARY
      |  TRACK 02 AUDIO
      |REM FILE "not a file.bin" BINARY
      |FILEX "nor this.bin" BINARY
      """
        .trimMargin()
    assertEquals(
      listOf("Game (Track 1).bin", "Game (Track 2).bin"),
      DiscSets.listedFiles("Game.cue", cue),
    )
  }

  @Test
  fun aGdiListsItsTracksAndAPlaylistItsDiscs() {
    val gdi =
      """
      |3
      |1 0 4 2352 track01.bin 0
      |2 756 0 2352 "track 02.raw" 0
      |3 45000 4 2352 track03.bin 0
      """
        .trimMargin()
    assertEquals(
      listOf("track01.bin", "track 02.raw", "track03.bin"),
      DiscSets.listedFiles("Game.GDI", gdi),
    )
    val m3u = "\uFEFF#EXTM3U\r\nGame (Disc 1).cue\r\n\r\nGame (Disc 2).cue\r\n"
    assertEquals(
      listOf("Game (Disc 1).cue", "Game (Disc 2).cue"),
      DiscSets.listedFiles("Game.m3u", m3u),
    )
    assertNull(DiscSets.listedFiles("Game.bin", "FILE \"x.bin\" BINARY"))
    assertTrue(DiscSets.isSheet("Game.CUE"))
    assertFalse(DiscSets.isSheet("Game.chd"))
  }

  @Test
  fun anUnquotedCueFileMayHaveATabBeforeItsType() {
    assertEquals(
      listOf("Game (Track 1).bin"),
      DiscSets.listedFiles("Game.cue", "FILE Game (Track 1).bin\tBINARY\n  TRACK 01 MODE2/2352\n"),
    )
  }

  @Test
  fun aGdisFieldsMayBeSeparatedByTabs() {
    val gdi = "2\n1\t0\t4\t2352\ttrack01.bin\t0\n2\t756\t0\t2352\t\"track 02.raw\"\t0\n"
    assertEquals(listOf("track01.bin", "track 02.raw"), DiscSets.listedFiles("Game.gdi", gdi))
  }

  @Test
  fun aPlaylistsHeaderIsInItsFirstDiscsFirstDataTrack() {
    val sheets =
      mapOf(
        "dir/game.m3u" to "\uFEFF# discs\n\nGame (Disc 1).cue\nGame (Disc 2).cue\n",
        "dir/Game (Disc 1).cue" to
          "\uFEFFFILE \"Disc 1\\Game (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n",
      )
    assertEquals("dir/Disc 1/Game (Track 1).bin", DiscSets.headerFile("dir/game.m3u", sheets::get))
    assertEquals("dir/game.chd", DiscSets.headerFile("dir/game.chd", sheets::get))
    assertNull(DiscSets.headerFile("dir/missing.cue", sheets::get))
  }

  @Test
  fun aPlaylistMayNameADiscByItsWholePath() {
    val m3u = "/storage/ROMs/psx/Game (Disc 1).chd\n"
    assertEquals("/storage/ROMs/psx/Game (Disc 1).chd", DiscSets.headerFile("dir/game.m3u") { m3u })
  }

  @Test
  fun aSheetsHeaderIsInItsFirstDataTrack() {
    val cue =
      "FILE \"a.bin\" BINARY\n TRACK 01 AUDIO\nfile\tb c.bin\tBINARY\n TRACK 02 MODE1/2352\n"
    assertEquals("b c.bin", DiscSets.headerFile("game.cue") { cue })
    // A GDI's high-density area starts at track 3.
    val gdi =
      "\n3\n1 0 4 2352 track01.bin 0\n2 450 0 2352 track02.raw 0\n" +
        "3\t45000\t4\t2352\t\"track 03.bin\"\t0\n"
    assertEquals("track 03.bin", DiscSets.headerFile("game.gdi") { gdi })
  }

  @Test
  fun aSheetIsUtf8ElseLatin1() {
    val cue = "FILE \"Pokémon (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n"
    // Latin-1 encodes each of these characters as the one byte of its code point.
    val latin1 = ByteArray(cue.length) { cue[it].code.toByte() }
    for (bytes in listOf(cue.encodeToByteArray(), latin1)) {
      val text = DiscSets.sheetText(bytes.size.toLong()) { bytes }
      assertEquals("dir/Pokémon (Track 1).bin", DiscSets.headerFile("dir/game.cue") { text })
    }
    assertNull(DiscSets.sheetText(1024 * 1024) { error("A big file isn't read") })
  }

  @Test
  fun aSheetThatNamesItselfHasNoHeaderFile() {
    val cue = "FILE \"loop.cue\" BINARY\n  TRACK 01 MODE1/2352\n"
    assertNull(DiscSets.headerFile("loop.cue") { cue })
  }

  @Test
  fun onlyFilesBesideTheDescriptorAreItsSiblings() {
    val names = listOf("Game (Track 1).bin", "Game (Track 2).BIN", "Other.bin", "Game.cue")
    assertEquals(
      listOf("Game (Track 1).bin", "Game (Track 2).BIN"),
      DiscSets.siblings(
        listOf(
          "Game (Track 1).bin",
          // Cue sheets made on Windows may differ in case.
          "./game (track 2).bin",
          "Game (Track 1).bin",
          "Missing.bin",
          "sub\\Other.bin",
          "../Other.bin",
        ),
        names,
      ),
    )
  }
}
