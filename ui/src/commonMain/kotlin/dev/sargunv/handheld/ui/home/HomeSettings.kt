package dev.sargunv.handheld.ui.home

import kotlinx.serialization.Serializable

/** A shelf on the home screen, below the game the user was last playing. */
enum class HomeShelf(val title: String) {
  RecentlyPlayed("Recently played"),
  Collections("Collections"),
  RecentlyAdded("Recently added to your server"),
}

/** Which home shelves show, and in what order. Every shelf shows by default. */
@Serializable
data class HomeSettings(
  /** The shelves in the order they show, hidden ones included. */
  val order: List<HomeShelf> = HomeShelf.entries,
  val hidden: Set<HomeShelf> = emptySet(),
) {
  /** The shelves that show, in order. */
  val visible: List<HomeShelf>
    get() = order.filter { it !in hidden }

  fun hide(shelf: HomeShelf) = copy(hidden = hidden + shelf)

  fun show(shelf: HomeShelf) = copy(hidden = hidden - shelf)

  /**
   * Moves [shelf] before the shelf above it among [shown], the shelves on screen. Does nothing to
   * the first one.
   */
  fun moveUp(shelf: HomeShelf, shown: List<HomeShelf> = visible) = swap(shelf, shown, -1)

  /**
   * Moves [shelf] after the shelf below it among [shown], the shelves on screen. Does nothing to
   * the last one.
   */
  fun moveDown(shelf: HomeShelf, shown: List<HomeShelf> = visible) = swap(shelf, shown, 1)

  private fun swap(shelf: HomeShelf, shown: List<HomeShelf>, step: Int): HomeSettings {
    if (shelf !in shown) return this
    val neighbor = shown.getOrNull(shown.indexOf(shelf) + step) ?: return this
    val reordered = order.map {
      when (it) {
        shelf -> neighbor
        neighbor -> shelf
        else -> it
      }
    }
    return copy(order = reordered)
  }
}
