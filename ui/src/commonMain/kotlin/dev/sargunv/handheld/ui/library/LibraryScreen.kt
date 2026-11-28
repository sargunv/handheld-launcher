package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.settings.LibraryTab
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.places.AppState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

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
 * A grid's [games], the ones [filter] keeps in [sort]'s order, and how many are on the device by
 * [statuses].
 */
@Immutable
internal class GridGames(
  val games: List<LibraryGame>,
  filter: LibraryFilter,
  sort: LibrarySort,
  val statuses: Map<Long, RomStatus>,
) {
  val shown = games.shown(filter, sort, statuses)

  /** Each letter's first game in [shown], for the A–Z jump, which only the sort by name has. */
  val index: Map<Char, Int> = if (sort == LibrarySort.Name) jumpIndex(shown) else emptyMap()

  val onDevice = games.count { statusOf(it, statuses) is RomStatus.OnDevice }
}

/**
 * What Library shows, which its upper and lower screens share: the shelves of [tab], and the grid
 * of games on it or on the shelf open over it, from [cache] while [load] runs. The tab is in the
 * settings.
 */
@Stable
class LibraryState
internal constructor(
  private val cache: LibraryCache,
  private val romStatuses: Flow<Map<Long, RomStatus>>,
  private val localGames: Flow<List<LocalGame>>,
  private val tab: () -> LibraryTab,
) {
  /** The key of the shelf focused on each tab, or open; a tab without one focuses its first. */
  private val shelfKeys = mutableStateMapOf<LibraryTab, String>()

  /** Where the shelf's games, open over its tab, were opened from, or null while the tab shows. */
  var openedFrom: OpenedFrom? by mutableStateOf(null)
    private set

  var filter: LibraryFilter by mutableStateOf(LibraryFilter.All)
  var sort: LibrarySort by mutableStateOf(LibrarySort.Name)

  /** Letters to jump to, which the shown games' grid carries out. */
  internal val jumps = Channel<Char>(Channel.CONFLATED)

  /** Moves focus to the first shown game under [letter]. */
  fun jumpTo(letter: Char) {
    jumps.trySend(letter)
  }

  private var platforms: List<Platform>? by mutableStateOf(null)
  private var collections: List<Collection>? by mutableStateOf(null)
  private var romPlatforms: Map<Long, Long>? by mutableStateOf(null)
  private var collectionRoms: Map<Pair<CollectionKind, Long>, Set<Long>>? by mutableStateOf(null)
  private var statuses: Map<Long, RomStatus>? by mutableStateOf(null)
  private var local: List<LocalGame>? by mutableStateOf(null)

  /** The ROMs of the last source to load. */
  private var loaded: LoadedRoms? by mutableStateOf(null)

  private val platformShelves by derivedStateOf {
    val platforms = platforms ?: return@derivedStateOf null
    platformShelves(platforms, local ?: return@derivedStateOf null)
  }
  private val collectionShelves by derivedStateOf { collections?.let(::collectionShelves) }
  private val platformSummaries by derivedStateOf { summariesOf(platformShelves) }
  private val collectionSummaries by derivedStateOf { summariesOf(collectionShelves) }

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
  internal fun shelves(tab: LibraryTab): List<LibraryShelf>? =
    when (tab) {
      LibraryTab.Platforms -> platformShelves
      LibraryTab.Collections -> collectionShelves
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> emptyList()
    }

  /** The shelves on [tab] with their counts of games, all and on this device. */
  internal fun summaries(tab: LibraryTab): List<ShelfSummary> =
    when (tab) {
      LibraryTab.Platforms -> platformSummaries
      LibraryTab.Collections -> collectionSummaries
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> emptyList()
    }

  /** The platforms of [collection]'s games, or null while they load. */
  internal fun platformsOf(collection: Collection): List<Platform>? =
    collectionPlatforms(
      collection,
      platforms ?: return null,
      romPlatforms ?: return null,
      collectionRoms ?: return null,
    )

  /** The shelf focused on the tab that shows, or open over it. */
  internal val shelf: LibraryShelf? by derivedStateOf { shelves(tab())?.shelfAt(shelfKey(tab())) }

  /** Where the grid that shows gets its games, or null while a tab of shelves shows. */
  internal val source: GridSource? by derivedStateOf {
    when (val tab = tab()) {
      LibraryTab.OnDevice,
      LibraryTab.AllGames -> GridSource.OfTab(tab)
      LibraryTab.Platforms,
      LibraryTab.Collections -> shelf?.takeIf { openedFrom != null }?.let(GridSource::OfShelf)
    }
  }

  /** The games of [source] under the sort and the filter, or null while they load. */
  internal val grid: GridGames? by derivedStateOf {
    val source = source ?: return@derivedStateOf null
    val roms = loaded?.takeIf { it.source == source.key }?.roms ?: return@derivedStateOf null
    val statuses = statuses ?: return@derivedStateOf null
    val local = local ?: return@derivedStateOf null
    val games =
      when (source) {
        // The local games are on the device, and in the whole library.
        is GridSource.OfTab ->
          (if (source.tab == LibraryTab.OnDevice) roms.onDevice(statuses) else roms) + local
        is GridSource.OfShelf ->
          when (val shelf = source.shelf) {
            // A platform's local games are in its grid too.
            is LibraryShelf.OfPlatform -> roms + local.filter { it.platformId == shelf.platform.id }
            is LibraryShelf.OfCollection -> roms
          }
      }
    GridGames(games, if (source.filters) filter else LibraryFilter.All, sort, statuses)
  }

  /** Keeps what Library shows up to date with [cache] and the device. It runs until cancelled. */
  internal suspend fun load() = coroutineScope {
    launch { cache.platforms().collect { platforms = it } }
    launch { cache.collections().collect { collections = it } }
    launch { cache.romPlatforms().collect { romPlatforms = it } }
    launch { cache.collectionRoms().collect { collectionRoms = it } }
    launch { romStatuses.collect { statuses = it } }
    launch { localGames.collect { local = it } }
    snapshotFlow { source }
      .distinctUntilChangedBy { it?.key }
      .collectLatest { source ->
        if (source != null) romsOf(source).collect { loaded = LoadedRoms(source.key, it) }
      }
  }

  private fun romsOf(source: GridSource): Flow<List<Rom>> =
    when (source) {
      is GridSource.OfTab -> cache.allRoms()
      is GridSource.OfShelf ->
        when (val shelf = source.shelf) {
          is LibraryShelf.OfPlatform -> cache.romsOnPlatform(shelf.platform.id)
          is LibraryShelf.OfCollection ->
            cache.romsInCollection(shelf.collection.kind, shelf.collection.id)
        }
    }

  private fun summariesOf(shelves: List<LibraryShelf>?): List<ShelfSummary> =
    shelfSummaries(
      shelves.orEmpty(),
      romPlatforms.orEmpty(),
      collectionRoms.orEmpty(),
      statuses.orEmpty(),
      local.orEmpty(),
    )
}

/** The [roms] of the grid source with the key [source]. */
private class LoadedRoms(val source: String, val roms: List<Rom>)

/**
 * Library: four tabs, which L and R switch between. On device and All games are grids of games.
 * Platforms and Collections are grids of shelves, each of which opens as a grid of its games, where
 * L and R step to the neighboring shelves and Back returns to the tab.
 */
@Composable
internal fun LibraryPlace(state: AppState) {
  val view = state.libraryView
  LaunchedEffect(view) { view.load() }
  // Each grid of games, sort and filter keeps its own focus and scroll position.
  val gridStates = rememberSaveableStateHolder()
  when (val source = view.source) {
    is GridSource.OfShelf -> ShelfGames(state, source, gridStates)
    else -> LibraryTabs(state, source, gridStates)
  }
}
