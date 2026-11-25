package dev.sargunv.handheld.data

import dev.sargunv.handheld.domain.Game

/** The library as this device knows it. A RomM-backed cache replaces this stub. */
interface Library {
  fun games(): List<Game>
}

/** A library with no games, for previews and the first launch. */
object EmptyLibrary : Library {
  override fun games(): List<Game> = emptyList()
}
