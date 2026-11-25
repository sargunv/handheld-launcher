package dev.sargunv.handheld.ui.demo

import dev.sargunv.handheld.domain.Game

/** A row of games on the home screen. */
data class Shelf(val id: String, val title: String, val games: List<Game>)

/** Made-up shelves for exercising focus and navigation before the library exists. */
object DemoLibrary {
  val shelves: List<Shelf> =
    listOf(
      shelf("snes", "Shelf A", 14),
      shelf("gba", "Shelf B", 4),
      shelf("ps1", "Shelf C", 24),
      shelf("n64", "Shelf D", 9),
      shelf("ps2", "Shelf E", 6),
    )

  fun game(id: Long): Game? = shelves.firstNotNullOfOrNull { s -> s.games.find { it.rommId == id } }

  private fun shelf(slug: String, title: String, count: Int): Shelf {
    val base = (slug.hashCode().toLong() and 0xFFFF) * 1000
    return Shelf(
      id = slug,
      title = title,
      games = List(count) { i -> Game(base + i, "$title ${i + 1}", slug) },
    )
  }
}
