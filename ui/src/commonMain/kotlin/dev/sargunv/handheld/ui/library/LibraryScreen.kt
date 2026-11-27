package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.ui.places.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A request to jump to the first game under [letter]. Each request is a new one. */
class Jump(val letter: Char)

/** Where a shelf's games were opened from, which Back on them returns to. */
enum class OpenedFrom {
  Tab,
  Home,
}

/** Where a grid's games come from: a tab of games, or a shelf open over its tab. */
@Immutable
internal sealed interface GridSource {
  /** Names the source, so each one keeps its own focus. */
  val key: String

  /** Whether the grid has a filter. */
  val filters: Boolean

  data class OfTab(val tab: LibraryTab) : GridSource {
    override val key = "tab:${tab.name}"

    // Every game on the On device tab is on the device, so it has nothing to filter.
    override val filters = tab != LibraryTab.OnDevice
  }

  data class OfShelf(val shelf: LibraryShelf) : GridSource {
    override val key = shelf.key
    override val filters = true
  }
}

/**
 * A grid of games: [games], and the ones its filter keeps in its sort's order, [shown]. [key] names
 * its source, sort and filter.
 */
@Immutable
internal class GridGames(
  val key: String,
  val games: List<LibraryGame>,
  val shown: List<LibraryGame>,
  sort: LibrarySort,
) {
  /** Each letter's first game in [shown], for the A–Z jump, which only the sort by name has. */
  val index: Map<Char, Int> = if (sort == LibrarySort.Name) jumpIndex(shown) else emptyMap()

  /** The letters the A–Z jump goes to. */
  val letters: List<Char>
    get() = index.keys.toList()
}

/**
 * What Library shows, which its upper and lower screens share: the shelves and the grid of games on
 * [tab], from [cache]. The tab is in the settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Stable
class LibraryState
internal constructor(
  private val cache: LibraryCache,
  private val deviceStates: StateFlow<Map<Long, DeviceState>>,
  private val localGames: StateFlow<List<LocalGame>>,
  private val tab: () -> LibraryTab,
  private val scope: CoroutineScope,
) {
  /** The key of the shelf focused on each tab, or open; a tab without one focuses its first. */
  private val shelfKeys = mutableStateMapOf<LibraryTab, String>()

  /** Where the shelf's games, open over its tab, were opened from, or null while the tab shows. */
  var openedFrom: OpenedFrom? by mutableStateOf(null)
    private set

  var filter: LibraryFilter by mutableStateOf(LibraryFilter.All)
  var sort: LibrarySort by mutableStateOf(LibrarySort.Name)

  /** A jump that the shown games haven't carried out yet. */
  var jump: Jump? by mutableStateOf(null)

  private fun <T> Flow<T>.shared(initial: T) =
    stateIn(scope, SharingStarted.WhileSubscribed(), initial)

  private val platforms = cache.platforms().shared(null)
  private val romPlatforms = cache.romPlatforms().shared(null)
  private val collectionRoms = cache.collectionRoms().shared(null)

  private val platformShelves =
    combine(platforms, localGames) { platforms, local ->
        platforms?.let { platformShelves(it, local) }
      }
      .shared(null)
  private val collectionShelves = cache.collections().map(::collectionShelves).shared(null)
  private val platformSummaries = summariesOf(platformShelves)
  private val collectionSummaries = summariesOf(collectionShelves)

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

  /** The shelves on [tab], or null while they load. Tabs of games have none. */
  internal fun shelves(tab: LibraryTab): StateFlow<List<LibraryShelf>?> =
    when (tab) {
      LibraryTab.Platforms -> platformShelves
      LibraryTab.Collections -> collectionShelves
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> NoShelves
    }

  /** The shelves on [tab] with their counts of games, all and on this device. */
  internal fun summaries(tab: LibraryTab): StateFlow<List<ShelfSummary>> =
    when (tab) {
      LibraryTab.Platforms -> platformSummaries
      LibraryTab.Collections -> collectionSummaries
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> NoSummaries
    }

  /** The platforms of [collection]'s games, or null while they load. */
  internal fun platformsOf(collection: Collection): Flow<List<Platform>?> =
    combine(platforms, romPlatforms, collectionRoms) { platforms, romPlatforms, collectionRoms ->
      if (platforms == null || romPlatforms == null || collectionRoms == null) null
      else collectionPlatforms(collection, platforms, romPlatforms, collectionRoms)
    }

  /** The [GridGames.key] of the grid from [source] under the current sort and filter. */
  internal fun gridKey(source: GridSource): String = gridKey(source, sort, filter)

  /**
   * The grid of games on the tab, or on the shelf open over it, or null while a tab of shelves
   * shows. A change of tab, shelf, sort or filter reaches it a moment later, and [GridGames.key]
   * says which one it shows.
   */
  internal val grid: StateFlow<GridGames?> = snapshotFlow {
    Triple(tab(), openedFrom, shelfKey(tab()))
  }
    .flatMapLatest { (tab, from, key) ->
      when (tab) {
        LibraryTab.OnDevice,
        LibraryTab.AllGames -> flowOf(GridSource.OfTab(tab))
        LibraryTab.Platforms,
        LibraryTab.Collections ->
          if (from == null) flowOf(null)
          else shelves(tab).map { shelves -> shelves?.shelfAt(key)?.let(GridSource::OfShelf) }
      }
    }
    .distinctUntilChanged()
    .flatMapLatest { source -> source?.let(::gridOf) ?: flowOf(null) }
    .shared(null)

  private fun gridOf(source: GridSource): Flow<GridGames> =
    combine(gamesOf(source), deviceStates, snapshotFlow { sort to filter }) {
      games,
      states,
      (sort, filter) ->
      val kept = if (source.filters) filter else LibraryFilter.All
      GridGames(gridKey(source, sort, filter), games, games.shown(kept, sort, states), sort)
    }

  private fun gamesOf(source: GridSource): Flow<List<LibraryGame>> =
    when (source) {
      // The local games are on the device, and in the whole library.
      is GridSource.OfTab ->
        combine(cache.allRoms(), deviceStates, localGames) { all, states, local ->
          (if (source.tab == LibraryTab.OnDevice) all.onDevice(states) else all) + local
        }
      is GridSource.OfShelf ->
        when (val shelf = source.shelf) {
          // A platform's local games are in its grid too.
          is LibraryShelf.OfPlatform ->
            combine(cache.romsOnPlatform(shelf.platform.id), localGames) { roms, local ->
              roms + local.filter { it.platformId == shelf.platform.id }
            }
          is LibraryShelf.OfCollection ->
            cache.romsInCollection(shelf.collection.kind, shelf.collection.id)
        }
    }

  private fun summariesOf(shelves: Flow<List<LibraryShelf>?>): StateFlow<List<ShelfSummary>> =
    combine(shelves, romPlatforms, collectionRoms, deviceStates, localGames) {
        loaded,
        romPlatforms,
        collectionRoms,
        states,
        local ->
        shelfSummaries(
          loaded.orEmpty(),
          romPlatforms.orEmpty(),
          collectionRoms.orEmpty(),
          states,
          local,
        )
      }
      .shared(emptyList())
}

private fun gridKey(source: GridSource, sort: LibrarySort, filter: LibraryFilter) =
  "${source.key}/$sort/${if (source.filters) filter else LibraryFilter.All}"

private val NoShelves = MutableStateFlow(emptyList<LibraryShelf>())
private val NoSummaries = MutableStateFlow(emptyList<ShelfSummary>())

/**
 * Library: four tabs, which L and R switch between. On device and All games are grids of games.
 * Platforms and Collections are grids of shelves, each of which opens as a grid of its games, where
 * L and R step to the neighboring shelves and Back returns to the tab.
 */
@Composable
internal fun LibraryPlace(state: AppState) {
  val view = state.libraryView
  val tab = state.libraryTab
  val shelves by view.shelves(tab).collectAsState()
  val shelf = shelves?.shelfAt(view.shelfKey(tab))
  // Each grid of games, sort and filter keeps its own focus and scroll position.
  val gridStates = rememberSaveableStateHolder()
  if (view.openedFrom != null && shelf != null) {
    ShelfGames(state, shelves.orEmpty(), shelf, gridStates)
  } else {
    LibraryTabs(state, tab, shelves, shelf, gridStates)
  }
}
