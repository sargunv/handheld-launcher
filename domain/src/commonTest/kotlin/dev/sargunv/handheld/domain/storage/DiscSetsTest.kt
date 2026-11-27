package dev.sargunv.handheld.domain.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscSetsTest {
  /** The sets among [names], whose sheets are [sheets]. */
  private fun sets(names: List<String>, sheets: Map<String, String>) =
    DiscSets.sets(names, sheets::get)

  @Test
  fun aCueSheetIsASetWithItsTracks() {
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
    val tracks = listOf("Game (Track 1).bin", "Game (Track 2).bin")
    assertEquals(
      mapOf("Game.cue" to tracks),
      sets(
        listOf("Game.cue") + tracks + listOf("not a file.bin", "nor this.bin"),
        mapOf("Game.cue" to cue),
      ),
    )
  }

  @Test
  fun aGdiIsASetWithItsTracks() {
    val gdi =
      """
      |3
      |1 0 4 2352 track01.bin 0
      |2 756 0 2352 "track 02.raw" 0
      |3 45000 4 2352 track03.bin 0
      """
        .trimMargin()
    val tracks = listOf("track01.bin", "track 02.raw", "track03.bin")
    assertEquals(
      mapOf("Game.GDI" to tracks),
      sets(listOf("Game.GDI") + tracks, mapOf("Game.GDI" to gdi)),
    )
  }

  @Test
  fun aPlaylistsDiscsPullInTheirTracks() {
    val sheets =
      mapOf(
        "Game.m3u" to "\uFEFF#EXTM3U\r\nGame (Disc 1).cue\r\n\r\nGame (Disc 2).cue\r\n",
        "Game (Disc 1).cue" to "FILE \"Game (Disc 1).bin\" BINARY\n",
        "Game (Disc 2).cue" to "FILE \"Game (Disc 2).bin\" BINARY\n",
      )
    val names =
      listOf(
        "Game (Disc 1).bin",
        "Game (Disc 1).cue",
        "Game (Disc 2).bin",
        "Game (Disc 2).cue",
        "Game.m3u",
      )
    // A sheet that another lists heads no set of its own.
    assertEquals(
      mapOf(
        "Game.m3u" to
          listOf(
            "Game (Disc 1).cue",
            "Game (Disc 1).bin",
            "Game (Disc 2).cue",
            "Game (Disc 2).bin",
          ),
      ),
      sets(names, sheets),
    )
  }

  @Test
  fun aCycleOfSheetsEnds() {
    val sheets =
      mapOf(
        "Set.m3u" to "a.cue\n",
        "a.cue" to "FILE \"b.cue\" BINARY\n",
        "b.cue" to "FILE \"a.cue\" BINARY\n",
        "c.cue" to "FILE \"d.cue\" BINARY\n",
        "d.cue" to "FILE \"c.cue\" BINARY\n",
      )
    // Sheets that only list each other head no set.
    assertEquals(mapOf("Set.m3u" to listOf("a.cue", "b.cue")), sets(sheets.keys.toList(), sheets))
  }

  @Test
  fun aSetTakesOnlyTheFilesBesideItsSheet() {
    val cue =
      listOf(
          "Game (Track 1).bin",
          // Cue sheets made on Windows may differ in case.
          "./game (track 2).bin",
          "Game (Track 1).bin",
          "Missing.bin",
          "sub\\Other.bin",
          "../Other.bin",
        )
        .joinToString("") { "FILE \"$it\" BINARY\n" }
    val names =
      listOf("Game (Track 1).bin", "Game (Track 2).BIN", "Game.cue", "Other.bin", "Stray.chd")
    // The files no sheet lists stay games of their own.
    assertEquals(
      mapOf("Game.cue" to listOf("Game (Track 1).bin", "Game (Track 2).BIN")),
      sets(names, mapOf("Game.cue" to cue)),
    )
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
}
