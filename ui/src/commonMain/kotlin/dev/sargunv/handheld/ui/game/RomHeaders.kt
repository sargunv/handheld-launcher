package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Rom

/** One field of a game's own header, in words, such as its serial. */
data class RomHeaderField(val label: String, val value: String)

/**
 * Reads what a game's file says about itself in its own header, such as a disc's serial or a
 * cartridge's region, for the Game screen. Each platform supplies its own.
 */
fun interface RomHeaders {
  /**
   * The fields of the header of [rom]'s file or folder at [path], or none when there's none to
   * read. It reads the file, so it runs off the main thread.
   */
  suspend fun read(rom: Rom, path: String): List<RomHeaderField>

  companion object {
    /** For platforms that don't read headers. */
    val None = RomHeaders { _, _ -> emptyList() }
  }
}
