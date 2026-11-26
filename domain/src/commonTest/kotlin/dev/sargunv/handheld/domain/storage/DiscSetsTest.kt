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
    val m3u = "﻿#EXTM3U\r\nGame (Disc 1).cue\r\n\r\nGame (Disc 2).cue\r\n"
    assertEquals(
      listOf("Game (Disc 1).cue", "Game (Disc 2).cue"),
      DiscSets.listedFiles("Game.m3u", m3u),
    )
    assertNull(DiscSets.listedFiles("Game.bin", "FILE \"x.bin\" BINARY"))
    assertTrue(DiscSets.isDescriptor("Game.CUE"))
    assertFalse(DiscSets.isDescriptor("Game.chd"))
  }

  @Test
  fun aGdisFieldsMayBeSeparatedByTabs() {
    val gdi = "2\n1\t0\t4\t2352\ttrack01.bin\t0\n2\t756\t0\t2352\t\"track 02.raw\"\t0\n"
    assertEquals(listOf("track01.bin", "track 02.raw"), DiscSets.listedFiles("Game.gdi", gdi))
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
