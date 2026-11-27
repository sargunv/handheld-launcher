package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.ui.places.AppState

/** A request to jump to the first game under [letter]. Each request is a new one. */
class Jump(val letter: Char)

/** Where a shelf's games were opened from, which Back on them returns to. */
enum class OpenedFrom {
  Tab,
  Home,
}

/** What Library shows, which its upper and lower screens share. Its tab is in the settings. */
@Stable
class LibraryState {
  /** The key of the shelf focused on each tab, or open; a tab without one focuses its first. */
  private val shelfKeys = mutableStateMapOf<LibraryTab, String>()

  /** Where the shelf's games, open over its tab, were opened from, or null while the tab shows. */
  var openedFrom: OpenedFrom? by mutableStateOf(null)
    private set

  var filter: LibraryFilter by mutableStateOf(LibraryFilter.All)
  var sort: LibrarySort by mutableStateOf(LibrarySort.Name)

  /** The letters the shown games can jump to, which the upper screen keeps up to date. */
  var letters: List<Char> by mutableStateOf(emptyList())
    internal set

  /** A jump that the shown games haven't carried out yet. */
  var jump: Jump? by mutableStateOf(null)

  /** The key of the shelf focused on [tab], or open, or null for its first. */
  fun shelfKey(tab: LibraryTab): String? = shelfKeys[tab]

  fun focusShelf(tab: LibraryTab, key: String) {
    shelfKeys[tab] = key
  }

  /** Opens the games of the shelf with [key] on [tab], over the tab. */
  fun open(tab: LibraryTab, key: String, from: OpenedFrom) {
    focusShelf(tab, key)
    openedFrom = from
  }

  /** Closes the open shelf, back to its tab. */
  fun close() {
    openedFrom = null
  }
}

/**
 * Library: four tabs, which L and R switch between. On device and All games are grids of games.
 * Platforms and Collections are grids of shelves, each of which opens as a grid of its games, where
 * L and R step to the neighboring shelves and Back returns to the tab.
 */
@Composable
internal fun LibraryPlace(state: AppState) {
  val view = state.libraryView
  val tab = state.libraryTab
  val shelves = rememberShelves(state, tab)
  val shelf = shelves?.shelfAt(view.shelfKey(tab))
  // Each grid of games, sort and filter keeps its own focus and scroll position.
  val gridStates = rememberSaveableStateHolder()
  if (view.openedFrom != null && shelf != null) {
    ShelfGames(state, shelves, shelf, gridStates)
  } else {
    LibraryTabs(state, tab, shelves, shelf, gridStates)
  }
}

/** The shelves on [tab], or null while they load. Tabs of games have none. */
@Composable
internal fun rememberShelves(state: AppState, tab: LibraryTab): List<LibraryShelf>? {
  val cache = state.library
  val platforms by remember(cache) { cache.platforms() }.collectAsState(null)
  val collections by remember(cache) { cache.collections() }.collectAsState(null)
  val local = state.localGames
  return remember(tab, platforms, collections, local) {
    when (tab) {
      LibraryTab.Platforms -> platforms?.let { platformShelves(it, local) }
      LibraryTab.Collections -> collections?.let(::collectionShelves)
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> emptyList()
    }
  }
}
