package dev.sargunv.handheld.ui.demo

/** A placeholder game on the demo screen. */
data class Tile(val id: Long, val title: String)

/** A row of tiles on the demo screen. */
data class Shelf(val id: String, val title: String, val tiles: List<Tile>)

/** Made-up shelves for exercising focus and navigation in the tests. */
object DemoLibrary {
  val shelves: List<Shelf> =
    listOf(
      shelf("snes", "Shelf A", 14),
      shelf("gba", "Shelf B", 4),
      shelf("ps1", "Shelf C", 24),
      shelf("n64", "Shelf D", 9),
      shelf("ps2", "Shelf E", 6),
    )

  fun tile(id: Long): Tile? = shelves.firstNotNullOfOrNull { s -> s.tiles.find { it.id == id } }

  private fun shelf(slug: String, title: String, count: Int): Shelf {
    val base = (slug.hashCode().toLong() and 0xFFFF) * 1000
    return Shelf(
      id = slug,
      title = title,
      tiles = List(count) { i -> Tile(base + i, "$title ${i + 1}") },
    )
  }
}
