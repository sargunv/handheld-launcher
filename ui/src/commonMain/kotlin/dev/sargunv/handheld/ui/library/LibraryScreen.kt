package dev.sargunv.handheld.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.FocusLazyVerticalGrid
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.launchId
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.LocalInputSettings
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.GameDescription
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.NOT_ON_SERVER
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.places.gameCount
import kotlinx.coroutines.flow.map

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
private fun rememberShelves(state: AppState, tab: LibraryTab): List<LibraryShelf>? {
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

/** The tabs, with [tab] showing. L and R switch to the neighboring tab, stopping at the ends. */
@Composable
private fun LibraryTabs(
  state: AppState,
  tab: LibraryTab,
  shelves: List<LibraryShelf>?,
  focused: LibraryShelf?,
  gridStates: SaveableStateHolder,
) {
  fun switchTo(next: LibraryTab?) {
    if (next == null || next == tab) return
    state.libraryTab = next
    state.focused = null
  }
  Column(
    Modifier.fillMaxSize().onAction { action ->
      when (action) {
        Action.PreviousShelf -> switchTo(LibraryTab.entries.getOrNull(tab.ordinal - 1))
        Action.NextShelf -> switchTo(LibraryTab.entries.getOrNull(tab.ordinal + 1))
        else -> return@onAction false
      }
      true
    },
  ) {
    TabRow(tab, onSelect = ::switchTo)
    Box(Modifier.fillMaxWidth().weight(1f)) {
      // Each tab is a focus scope of its own, which takes focus as it shows.
      key(tab) {
        when (tab) {
          LibraryTab.OnDevice,
          LibraryTab.AllGames -> TabGames(state, tab, gridStates)
          LibraryTab.Platforms,
          LibraryTab.Collections -> ShelfList(state, tab, shelves, focused)
        }
      }
    }
  }
}

/** The names of the tabs, [current] marked, between the buttons that switch them. */
@Composable
private fun TabRow(current: LibraryTab, onSelect: (LibraryTab) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      buttonLabel(Action.PreviousShelf),
      color = Placeholder.mutedText,
      fontSize = 14.sp,
      modifier = Modifier.clearAndSetSemantics {},
    )
    for (tab in LibraryTab.entries) {
      val selected = tab == current
      val currentOnSelect by rememberUpdatedState(onSelect)
      Text(
        tab.title,
        color = if (selected) Placeholder.text else Placeholder.mutedText,
        fontSize = 18.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier =
          Modifier.testTag("library:tab:${tab.name}")
            .semantics {
              role = Role.Tab
              this.selected = selected
              onClick {
                currentOnSelect(tab)
                true
              }
            }
            .pointerInput(tab) { detectTapGestures { currentOnSelect(tab) } },
      )
    }
    Text(
      buttonLabel(Action.NextShelf),
      color = Placeholder.mutedText,
      fontSize = 14.sp,
      modifier = Modifier.clearAndSetSemantics {},
    )
  }
}

/** The label of the button that performs [action] under the player's input settings. */
@Composable
private fun buttonLabel(action: Action): String {
  val settings = LocalInputSettings.current
  return PadButton.entries.firstOrNull { settings.actionFor(it) == action }?.label.orEmpty()
}

/** The games of a tab of games: those on the device or on their way, or the whole library. */
@Composable
private fun TabGames(state: AppState, tab: LibraryTab, gridStates: SaveableStateHolder) {
  val cache = state.library
  val all by remember(cache) { cache.allRoms() }.collectAsState(null)
  val onDevice = tab == LibraryTab.OnDevice
  // The local games are on the device, and in the whole library.
  val local = state.localGames
  val roms =
    remember(all, onDevice, state.deviceStates, local) {
      (if (onDevice) all?.onDevice(state.deviceStates) else all)?.let { it + local }
    }
  GameGridPane(
    state = state,
    gridStates = gridStates,
    sourceKey = "tab:${tab.name}",
    title = tab.title,
    roms = roms,
    // Every game on the On device tab is on the device, so it has nothing to filter.
    filters = !onDevice,
    empty =
      if (onDevice) "No games on this device yet. A game's page downloads it."
      else librarySyncText(state.sync),
    backHint = "Home",
  )
}

/** What a tab with nothing to show says, by how the sync with the server went. */
internal fun librarySyncText(sync: SyncStatus, synced: String = "Your library has no games yet.") =
  when (sync) {
    SyncStatus.Failed -> "Couldn't reach your server to sync your library."
    SyncStatus.Synced -> synced
    else -> "Syncing your library"
  }

/**
 * The platforms or collections of [tab], focused on [focused]. Confirm opens the focused one, and
 * the menu offers Open with for its games.
 */
@Composable
private fun ShelfList(
  state: AppState,
  tab: LibraryTab,
  shelves: List<LibraryShelf>?,
  focused: LibraryShelf?,
) {
  val view = state.libraryView
  val summaries = rememberShelfSummaries(state, shelves.orEmpty())
  var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
  val menuShelf = shelves?.find { it.key == menuFor }
  val hasShelves = summaries.isNotEmpty()
  // A new scope each time the tab shows, so focus lands on the shelf that was open, even when L
  // and R moved on from the one that opened.
  FocusScope(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Menu && hasShelves && focused != null) {
        menuFor = focused.key
        true
      } else {
        false
      }
    },
    initialFocus = focused?.let(::shelfTileKey),
  ) {
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        if (shelves != null && shelves.isEmpty()) {
          Text(
            if (tab == LibraryTab.Collections) {
              librarySyncText(state.sync, synced = "Your server has no collections.")
            } else {
              librarySyncText(state.sync)
            },
            color = Placeholder.mutedText,
            modifier = Modifier.align(Alignment.Center),
          )
        }
        if (hasShelves) {
          ShelfTiles(
            summaries = summaries,
            focused = focused,
            onFocus = { view.focusShelf(tab, it.key) },
            onOpen = {
              view.open(tab, it.key, OpenedFrom.Tab)
              state.focused = null
            },
          )
        }
      }
      HintBar(
        buildList {
          if (hasShelves) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Back, "Home"))
          if (hasShelves) add(Hint(Action.Menu, "Menu"))
          add(Hint(Action.MainMenu, "Main menu"))
        },
      )
    }
    if (menuShelf != null) {
      ShelfMenu(state, menuShelf, onDismissRequest = { menuFor = null })
    }
  }
}

@Composable
private fun ShelfTiles(
  summaries: List<ShelfSummary>,
  focused: LibraryShelf?,
  onFocus: (LibraryShelf) -> Unit,
  onOpen: (LibraryShelf) -> Unit,
) {
  // The list starts at the focused shelf, so its tile is there for focus to come back to.
  val grid =
    rememberLazyGridState(
      initialFirstVisibleItemIndex =
        summaries.indexOfFirst { it.shelf == focused }.coerceAtLeast(0),
    )
  FocusLazyVerticalGrid(
    columns = GridCells.Adaptive(220.dp),
    modifier = Modifier.fillMaxSize().testTag("library:shelves"),
    state = grid,
    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(summaries, key = { it.shelf.key }) { summary ->
      val key = shelfTileKey(summary.shelf)
      Box(
        Modifier.onFocusChanged { if (it.isFocused) onFocus(summary.shelf) }
          .focusItem(
            key,
            onConfirm = { onOpen(summary.shelf) },
            description = summary.description,
          )
          .height(112.dp)
          .background(Placeholder.tile)
          .padding(16.dp),
      ) {
        Column(
          Modifier.align(Alignment.BottomStart),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(summary.shelf.title, color = Placeholder.text, fontSize = 18.sp)
          Text(gameCount(summary.games), color = Placeholder.mutedText, fontSize = 14.sp)
          if (summary.onDevice > 0) {
            Text(
              "${summary.onDevice} on this device",
              color = Placeholder.mutedText,
              fontSize = 14.sp,
            )
          }
        }
      }
    }
  }
}

private fun shelfTileKey(shelf: LibraryShelf) = "library:${shelf.key}"

/** The [shelves] with their counts of games, all and on this device. */
@Composable
private fun rememberShelfSummaries(
  state: AppState,
  shelves: List<LibraryShelf>,
): List<ShelfSummary> {
  val cache = state.library
  val romPlatforms by remember(cache) { cache.romPlatforms() }.collectAsState(emptyMap())
  val collectionRoms by remember(cache) { cache.collectionRoms() }.collectAsState(emptyMap())
  val local = state.localGames
  return remember(shelves, romPlatforms, collectionRoms, state.deviceStates, local) {
    shelfSummaries(shelves, romPlatforms, collectionRoms, state.deviceStates, local)
  }
}

/**
 * The menu on a platform or a collection: Open with, for all of its games. A collection's games
 * open by platform, so it offers Open with for each platform among them.
 */
@Composable
private fun ShelfMenu(state: AppState, shelf: LibraryShelf, onDismissRequest: () -> Unit) {
  val cache = state.library
  val choices: List<Pair<String, OpenWithScope>>? =
    when (shelf) {
      is LibraryShelf.OfPlatform ->
        listOf("Open with" to OpenWithScope.Platform(PlatformSlug(shelf.platform.slug)))
      is LibraryShelf.OfCollection -> {
        val platforms by remember(cache) { cache.platforms() }.collectAsState(null)
        val romPlatforms by remember(cache) { cache.romPlatforms() }.collectAsState(null)
        val collectionRoms by remember(cache) { cache.collectionRoms() }.collectAsState(null)
        val loaded = platforms != null && romPlatforms != null && collectionRoms != null
        if (!loaded) {
          null
        } else {
          val among =
            collectionPlatforms(
              shelf.collection,
              platforms.orEmpty(),
              romPlatforms.orEmpty(),
              collectionRoms.orEmpty(),
            )
          among.map { platform ->
            val label = if (among.size == 1) "Open with" else "Open ${platform.name} games with"
            label to
              OpenWithScope.Collection(shelf.collection.launchId, PlatformSlug(platform.slug))
          }
        }
      }
    }
  Dialog(onDismissRequest) {
    // It scrolls with focus, so a collection of many platforms still reaches the last.
    Column(
      Modifier.verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(shelf.title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      if (choices != null && choices.isEmpty()) {
        Text("None of its games are in your library.", color = Placeholder.mutedText)
      }
      for ((label, scope) in choices.orEmpty()) {
        val tag =
          when (scope) {
            is OpenWithScope.Collection -> "menu:open-with:${scope.platform.value}"
            else -> "menu:open-with"
          }
        MenuButton(tag, label) {
          onDismissRequest()
          state.navigator.push(Screen.OpenWith(scope, shelf.title))
        }
      }
    }
  }
}

/**
 * The games of [shelf], open from its tab. L and R step between shelves, stopping at the ends, and
 * Back returns to the tab, or Home when Home opened it.
 */
@Composable
private fun ShelfGames(
  state: AppState,
  shelves: List<LibraryShelf>,
  shelf: LibraryShelf,
  gridStates: SaveableStateHolder,
) {
  val cache = state.library
  val view = state.libraryView
  val tab = state.libraryTab
  val shelfKey = shelf.key
  // Each value carries its shelf's key, so a shelf never shows the games of the one before it.
  val loaded by
    remember(cache, shelf) {
        when (shelf) {
          is LibraryShelf.OfPlatform -> cache.romsOnPlatform(shelf.platform.id)
          is LibraryShelf.OfCollection ->
            cache.romsInCollection(shelf.collection.kind, shelf.collection.id)
        }.map { shelfKey to it }
      }
      .collectAsState(null)
  // A platform's local games are in its grid too.
  val local = state.localGames
  val roms =
    remember(loaded, shelf, local) {
      loaded
        ?.takeIf { it.first == shelfKey }
        ?.second
        ?.let { roms ->
          when (shelf) {
            is LibraryShelf.OfPlatform -> roms + local.filter { it.platformId == shelf.platform.id }
            is LibraryShelf.OfCollection -> roms
          }
        }
    }

  fun moveTo(next: LibraryShelf?) {
    if (next == null) return
    view.focusShelf(tab, next.key)
    state.focused = null
  }

  fun close() {
    state.focused = null
    if (view.openedFrom == OpenedFrom.Home) {
      // Leaving the Library closes the shelf too.
      state.showPlace(Place.Home)
    } else {
      view.close()
    }
  }

  BackHandler(
    onBack = ::close,
    Modifier.fillMaxSize().onAction { action ->
      when (action) {
        // At either end, L and R stay on the shelf rather than leave it for another tab.
        Action.PreviousShelf -> moveTo(shelves.step(shelfKey, -1))
        Action.NextShelf -> moveTo(shelves.step(shelfKey, 1))
        else -> return@onAction false
      }
      true
    },
  ) {
    GameGridPane(
      state = state,
      gridStates = gridStates,
      sourceKey = shelfKey,
      title = shelf.title,
      roms = roms,
      filters = true,
      empty = "No games here yet.",
      backHint = if (view.openedFrom == OpenedFrom.Home) "Home" else "Back",
    ) {
      ShelfHeader(
        shelves,
        shelf,
        roms?.size,
        roms?.count { deviceStateOf(it, state.deviceStates) == DeviceState.OnDevice },
      )
    }
  }
}

/**
 * A grid of [roms], with the sort, the filter when [filters] allows one, and the A–Z jump, which
 * the menu and the lower screen change. [sourceKey] names where the games come from, so each grid
 * keeps its own focus for each sort and filter. The grid says [empty] when [roms] is.
 */
@Composable
private fun GameGridPane(
  state: AppState,
  gridStates: SaveableStateHolder,
  sourceKey: String,
  title: String,
  roms: List<LibraryGame>?,
  filters: Boolean,
  empty: String,
  backHint: String,
  header: @Composable () -> Unit = {},
) {
  val view = state.libraryView
  val filter = if (filters) view.filter else LibraryFilter.All
  val shown =
    remember(roms, filter, view.sort, state.deviceStates) {
      roms?.shown(filter, view.sort, state.deviceStates)
    }
  val index =
    remember(shown, view.sort) {
      if (shown != null && view.sort == LibrarySort.Name) jumpIndex(shown) else emptyMap()
    }
  SideEffect { view.letters = index.keys.toList() }
  // The lower screen describes nothing while the grid has no game to focus.
  val nothingShown = shown.isNullOrEmpty()
  LaunchedEffect(nothingShown) { if (nothingShown) state.focused = null }
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  // The menu sorts and filters, which a grid with no games at all has no use for.
  val hasMenu = !roms.isNullOrEmpty()

  Box(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Menu && hasMenu) {
        menuOpen = true
        true
      } else {
        false
      }
    },
  ) {
    Column(Modifier.fillMaxSize()) {
      header()
      Box(Modifier.fillMaxWidth().weight(1f)) {
        val stateKey = "$sourceKey/${view.sort}/$filter"
        gridStates.SaveableStateProvider(stateKey) {
          key(stateKey) {
            FocusScope(Modifier.fillMaxSize()) {
              val message =
                when {
                  roms == null -> null
                  roms.isEmpty() -> empty
                  shown.isNullOrEmpty() -> "No games here match the filter."
                  else -> null
                }
              if (message != null) {
                Text(
                  message,
                  color = Placeholder.mutedText,
                  modifier = Modifier.align(Alignment.Center),
                )
              }
              if (!shown.isNullOrEmpty()) {
                GameGrid(state, shown, index)
              }
            }
          }
        }
      }
      HintBar(
        buildList {
          if (state.focused is Focused.OnGame && !nothingShown) {
            add(Hint(Action.Confirm, "Open"))
          }
          add(Hint(Action.Back, backHint))
          if (hasMenu) add(Hint(Action.Menu, "Menu"))
          add(Hint(Action.MainMenu, "Main menu"))
        },
      )
    }
    if (menuOpen) {
      GamesMenu(
        state = state,
        title = title,
        filters = filters,
        letters = index.keys.toList(),
        onDismissRequest = { menuOpen = false },
      )
    }
  }
}

@Composable
private fun ShelfHeader(
  shelves: List<LibraryShelf>,
  shelf: LibraryShelf,
  games: Int?,
  onDevice: Int?,
) {
  val at = shelves.indexOf(shelf)
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    shelves.getOrNull(at - 1)?.let { previous ->
      Text(
        "${buttonLabel(Action.PreviousShelf)} ${previous.title}",
        color = Placeholder.mutedText,
        fontSize = 14.sp,
      )
    }
    Text(
      shelf.title,
      color = Placeholder.text,
      fontSize = 24.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("shelf:${shelf.key}").semantics { heading() },
    )
    shelves.getOrNull(at + 1)?.let { next ->
      Text(
        "${next.title} ${buttonLabel(Action.NextShelf)}",
        color = Placeholder.mutedText,
        fontSize = 14.sp,
      )
    }
    Spacer(Modifier.weight(1f))
    if (games != null && onDevice != null) {
      Text("${gameCount(games)}, $onDevice on this device", color = Placeholder.mutedText)
    }
  }
}

@Composable
private fun GameGrid(state: AppState, shown: List<LibraryGame>, index: Map<Char, Int>) {
  val view = state.libraryView
  val grid = rememberLazyGridState()
  val scope = LocalFocusScope.current
  val jump = view.jump
  LaunchedEffect(jump) {
    if (jump == null) return@LaunchedEffect
    try {
      val target = index[jump.letter] ?: return@LaunchedEffect
      grid.scrollToItem(target)
      // The grid composes the items it scrolled to during the next layout.
      withFrameNanos {}
      scope?.focus(gameTag(shown[target]))
    } finally {
      // Clearing it any sooner would cancel this effect.
      if (view.jump === jump) view.jump = null
    }
  }
  FocusLazyVerticalGrid(
    columns = GridCells.Adaptive(100.dp),
    modifier = Modifier.fillMaxSize().testTag("library:grid"),
    state = grid,
    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(shown, key = { gameTag(it) }) { game ->
      val deviceState = deviceStateOf(game, state.deviceStates)
      LibraryCover(
        game = game,
        deviceState = deviceState,
        modifier =
          Modifier.aspectRatio(3f / 4f)
            .onFocusChanged { if (it.isFocused) state.focused = Focused.OnGame(game) }
            .focusItem(
              gameTag(game),
              onConfirm = { state.navigator.push(Screen.Game(game.key, game.title)) },
              stateDescription = deviceStateText(deviceState),
            ),
      )
    }
  }
}

/** What a screen reader says for a game's [DeviceState]. */
private fun deviceStateText(state: DeviceState?) =
  when (state) {
    DeviceState.OnDevice -> "On this device"
    is DeviceState.Downloading -> "Downloading"
    null -> "Not on this device"
  }

/**
 * A game's cover, marked when the game is on the device or on its way. A local game has no cover,
 * so its tile shows its title, and says it's not on the server.
 */
@Composable
private fun LibraryCover(game: LibraryGame, deviceState: DeviceState?, modifier: Modifier) {
  Box(modifier) {
    when (game) {
      is Rom -> Cover(game.coverSmallUrl, game.title, Modifier.fillMaxSize())
      is LocalGame ->
        Cover(
          null,
          game.title,
          Modifier.fillMaxSize(),
          description = "${game.title}, $NOT_ON_SERVER",
        )
    }
    when (deviceState) {
      DeviceState.OnDevice ->
        Box(
          Modifier.align(Alignment.TopEnd)
            .padding(8.dp)
            .size(12.dp)
            .background(Placeholder.text, CircleShape)
            .testTag(
              when (game) {
                is Rom -> "onDevice:${game.id}"
                is LocalGame -> "onDevice:local:${game.path}"
              },
            ),
        )
      is DeviceState.Downloading ->
        Box(
          Modifier.align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.Black.copy(alpha = 0.6f)),
        ) {
          Box(
            Modifier.fillMaxHeight()
              .fillMaxWidth(deviceState.progress ?: 1f)
              .background(
                if (deviceState.progress == null) Placeholder.mutedText else Placeholder.text,
              ),
          )
        }
      null -> Unit
    }
  }
}

/**
 * The menu on a grid of games: sort, filter when [filters] allows one, and the A–Z jump, the same
 * choices the lower screen offers to touch. Each choice closes the menu.
 */
@Composable
private fun GamesMenu(
  state: AppState,
  title: String,
  filters: Boolean,
  letters: List<Char>,
  onDismissRequest: () -> Unit,
) {
  val view = state.libraryView
  fun choose(action: () -> Unit) {
    action()
    onDismissRequest()
  }
  Dialog(onDismissRequest) {
    // It scrolls with focus, so a short screen still reaches the letters.
    Column(
      Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      for (sort in LibrarySort.entries.filter { it != view.sort }) {
        MenuButton("menu:sort:${sort.name}", "Sort by ${sort.title.lowercase()}") {
          choose { view.sort = sort }
        }
      }
      if (filters) {
        for (filter in LibraryFilter.entries.filter { it != view.filter }) {
          val label =
            when (filter) {
              LibraryFilter.All -> "Show all games"
              LibraryFilter.OnDevice -> "Show only games on this device"
              LibraryFilter.NotOnDevice -> "Show only games not on this device"
            }
          MenuButton("menu:filter:${filter.name}", label) { choose { view.filter = filter } }
        }
      }
      if (letters.isNotEmpty()) {
        Text("Jump to", color = Placeholder.mutedText)
        // One row that scrolls with focus, so the whole alphabet fits on the screen.
        FocusLazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(letters) { letter ->
            MenuButton("menu:jump:$letter", letter.toString()) {
              choose { view.jump = Jump(letter) }
            }
          }
        }
      }
    }
  }
}

/**
 * Library's lower screen. On a tab of platforms or collections, the focused one. On a grid of
 * games, the focused game and touch shortcuts for the filter, the sort and the A–Z jump, which the
 * menu on the upper screen offers too.
 */
@Composable
internal fun LibrarySecondary(state: AppState) {
  val tab = state.libraryTab
  when {
    state.libraryView.openedFrom != null && tab in ShelfTabs ->
      GamesSecondary(state, filters = true)
    tab in ShelfTabs -> ShelfListSecondary(state, tab)
    else -> GamesSecondary(state, filters = tab != LibraryTab.OnDevice)
  }
}

/** The tabs of platforms and collections, rather than of games. */
private val ShelfTabs = setOf(LibraryTab.Platforms, LibraryTab.Collections)

@Composable
private fun ShelfListSecondary(state: AppState, tab: LibraryTab) {
  val shelves = rememberShelves(state, tab).orEmpty()
  val shelf = shelves.shelfAt(state.libraryView.shelfKey(tab))
  val summary = rememberShelfSummaries(state, shelves).find { it.shelf == shelf }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (summary != null) {
      Text(
        summary.shelf.title,
        color = Placeholder.text,
        fontSize = 24.sp,
        modifier = Modifier.semantics { heading() },
      )
      Text(gameCount(summary.games), color = Placeholder.mutedText)
      Text("${summary.onDevice} on this device", color = Placeholder.mutedText)
      val about = (summary.shelf as? LibraryShelf.OfCollection)?.collection?.description
      if (!about.isNullOrBlank()) Text(about, color = Placeholder.mutedText)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamesSecondary(state: AppState, filters: Boolean) {
  val view = state.libraryView
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.fillMaxWidth().weight(1f)) {
      (state.focused as? Focused.OnGame)?.let { GameDescription(state, it.game) }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (filters) {
        for (filter in LibraryFilter.entries) {
          TouchChip("touch:filter:${filter.name}", filter.title, view.filter == filter) {
            view.filter = filter
          }
        }
      }
      val nextSort = LibrarySort.entries[(view.sort.ordinal + 1) % LibrarySort.entries.size]
      TouchChip("touch:sort", "Sort: ${view.sort.title}", selected = false) {
        view.sort = nextSort
      }
    }
    if (view.letters.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        for (letter in view.letters) {
          TouchChip("touch:jump:$letter", letter.toString(), selected = false) {
            view.jump = Jump(letter)
          }
        }
      }
    }
  }
}

/** A button on the lower screen, for touch only. */
@Composable
private fun TouchChip(tag: String, label: String, selected: Boolean, onTap: () -> Unit) {
  val currentOnTap by rememberUpdatedState(onTap)
  Box(
    Modifier.testTag(tag)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        this.selected = selected
        onClick {
          currentOnTap()
          true
        }
      }
      .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
      .background(if (selected) Placeholder.text else Placeholder.tile)
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Text(label, color = if (selected) Placeholder.background else Placeholder.text)
  }
}
