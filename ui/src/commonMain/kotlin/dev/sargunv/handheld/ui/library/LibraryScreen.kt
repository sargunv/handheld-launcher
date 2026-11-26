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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.FocusLazyVerticalGrid
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.LocalInputSettings
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.GameDescription
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.places.gameCount
import kotlinx.coroutines.flow.map

/** A request to jump to the first game under [letter]. Each request is a new one. */
class Jump(val letter: Char)

/** What Library shows, which its upper and lower screens share. */
@Stable
class LibraryState {
  /** The key of the shelf focused on the list, or open, or null for the first. */
  var shelfKey: String? by mutableStateOf(null)

  /** Whether the shelf's games are open, rather than the list of shelves. */
  var isOpen: Boolean by mutableStateOf(false)
  var filter: LibraryFilter by mutableStateOf(LibraryFilter.All)
  var sort: LibrarySort by mutableStateOf(LibrarySort.Name)

  /** The letters the shown games can jump to, which the upper screen keeps up to date. */
  var letters: List<Char> by mutableStateOf(emptyList())
    internal set

  /** A jump that the shown shelf hasn't carried out yet. */
  var jump: Jump? by mutableStateOf(null)
}

/**
 * Library: a list of shelves, each a platform or the whole library, from the library cache. A shelf
 * opens as a grid of its games, where L and R step to the neighboring shelves and Back returns to
 * the list.
 */
@Composable
internal fun LibraryPlace(state: AppState) {
  val cache = state.library
  val view = state.libraryView
  val settings = state.settings.library
  val platforms by remember(cache) { cache.platforms() }.collectAsState(null)
  val shelves =
    remember(platforms, settings.allGames) {
      libraryShelves(platforms.orEmpty(), settings.allGames)
    }
  val shelf = shelves.shelfAt(view.shelfKey)
  // Each shelf, sort and filter keeps its own focus and scroll position, while the list shows too.
  val shelfStates = rememberSaveableStateHolder()
  if (view.isOpen && shelf != null) {
    ShelfGames(state, shelves, shelf, shelfStates)
  } else {
    ShelfList(state, shelves, shelf, loaded = platforms != null)
  }
}

/** The list of shelves, focused on [focused]. Confirm opens the focused shelf. */
@Composable
private fun ShelfList(
  state: AppState,
  shelves: List<LibraryShelf>,
  focused: LibraryShelf?,
  loaded: Boolean,
) {
  val view = state.libraryView
  val summaries = rememberShelfSummaries(state, shelves)
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  // A new scope each time the list shows, so focus lands on the shelf that was open, even when L
  // and R moved on from the one that opened.
  FocusScope(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Menu) {
        menuOpen = true
        true
      } else {
        false
      }
    },
    initialFocus = focused?.let(::shelfTileKey),
  ) {
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        if (loaded && shelves.isEmpty()) {
          Text(
            when (state.sync) {
              SyncStatus.Failed -> "Couldn't reach your server to sync your library."
              SyncStatus.Synced -> "Your library has no games yet."
              else -> "Syncing your library"
            },
            color = Placeholder.mutedText,
            modifier = Modifier.align(Alignment.Center),
          )
        }
        if (summaries.isNotEmpty()) {
          ShelfTiles(
            summaries = summaries,
            focused = focused,
            onFocus = { view.shelfKey = it.key },
            onOpen = {
              view.shelfKey = it.key
              view.isOpen = true
              state.focused = null
            },
          )
        }
      }
      HintBar(
        buildList {
          if (summaries.isNotEmpty()) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Menu, "Menu"))
          add(Hint(Action.QuickMenu, "Quick menu"))
        },
      )
    }
    if (menuOpen) {
      ListMenu(state, onDismissRequest = { menuOpen = false })
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
        Modifier.testTag(key)
          .semantics {
            contentDescription = summary.description
            role = Role.Button
            onClick {
              onOpen(summary.shelf)
              true
            }
          }
          .onFocusChanged { if (it.isFocused) onFocus(summary.shelf) }
          .focusItem(key, onConfirm = { onOpen(summary.shelf) })
          .height(112.dp)
          .background(Placeholder.tile)
          .padding(16.dp),
      ) {
        // The tile's description says all of this once.
        Column(
          Modifier.align(Alignment.BottomStart).clearAndSetSemantics {},
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
  return remember(shelves, romPlatforms, state.deviceStates) {
    shelfSummaries(shelves, romPlatforms, state.deviceStates)
  }
}

/** The games of [shelf], open from the list. L and R step between shelves, and Back returns. */
@Composable
private fun ShelfGames(
  state: AppState,
  shelves: List<LibraryShelf>,
  shelf: LibraryShelf,
  shelfStates: SaveableStateHolder,
) {
  val cache = state.library
  val view = state.libraryView
  val shelfKey = shelf.key
  // Each value carries its shelf's key, so a shelf never shows the games of the one before it.
  val loaded by
    remember(cache, shelf) {
        when (shelf) {
          LibraryShelf.AllGames -> cache.allRoms()
          is LibraryShelf.OfPlatform -> cache.romsOnPlatform(shelf.platform.id)
        }.map { shelfKey to it }
      }
      .collectAsState(null)
  val roms = loaded?.takeIf { it.first == shelfKey }?.second
  val shown =
    remember(roms, view.filter, view.sort, state.deviceStates) {
      roms?.shown(view.filter, view.sort, state.deviceStates)
    }
  val index =
    remember(shown, view.sort) {
      if (shown != null && view.sort == LibrarySort.Name) jumpIndex(shown) else emptyMap()
    }
  SideEffect { view.letters = index.keys.toList() }
  // The lower screen describes nothing while the grid has no game to focus.
  val empty = shown.isNullOrEmpty()
  LaunchedEffect(empty) { if (empty) state.focused = null }
  var menuOpen by rememberSaveable { mutableStateOf(false) }

  fun moveTo(next: LibraryShelf?) {
    if (next == null) return
    view.shelfKey = next.key
    state.focused = null
  }

  fun close() {
    view.isOpen = false
    state.focused = null
  }

  // The system back gesture returns to the list too, as Back does.
  if (LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { close() },
    )
  }

  Box(
    Modifier.fillMaxSize().onAction { action ->
      when (action) {
        // At either end, L and R stay on the shelf rather than leave it for another place.
        Action.PreviousShelf -> moveTo(shelves.step(shelfKey, -1))
        Action.NextShelf -> moveTo(shelves.step(shelfKey, 1))
        Action.Back -> close()
        Action.Menu -> menuOpen = true
        else -> return@onAction false
      }
      true
    },
  ) {
    Column(Modifier.fillMaxSize()) {
      ShelfHeader(
        shelves,
        shelf,
        roms?.size,
        roms?.count { state.deviceStates[it.id] == DeviceState.OnDevice },
      )
      Box(Modifier.fillMaxWidth().weight(1f)) {
        val stateKey = "${shelfKey}/${view.sort}/${view.filter}"
        shelfStates.SaveableStateProvider(stateKey) {
          key(stateKey) {
            FocusScope(Modifier.fillMaxSize()) {
              if (shown != null && shown.isEmpty()) {
                Text(
                  "No games here match the filter.",
                  color = Placeholder.mutedText,
                  modifier = Modifier.align(Alignment.Center),
                )
              }
              if (!shown.isNullOrEmpty()) {
                ShelfGrid(state, shown, index)
              }
            }
          }
        }
      }
      HintBar(
        buildList {
          if (state.focused is Focused.OnGame) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Back, "Back"))
          add(Hint(Action.Menu, "Menu"))
          add(Hint(Action.QuickMenu, "Quick menu"))
        },
      )
    }
    if (menuOpen) {
      ShelfMenu(
        state = state,
        title = shelf.title,
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
  val settings = LocalInputSettings.current
  fun buttonFor(action: Action) =
    PadButton.entries.firstOrNull { settings.actionFor(it) == action }?.label.orEmpty()
  val at = shelves.indexOf(shelf)
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    shelves.getOrNull(at - 1)?.let { previous ->
      Text(
        "${buttonFor(Action.PreviousShelf)} ${previous.title}",
        color = Placeholder.mutedText,
        fontSize = 14.sp,
      )
    }
    Text(
      shelf.title,
      color = Placeholder.text,
      fontSize = 24.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("shelf:${shelf.key}"),
    )
    shelves.getOrNull(at + 1)?.let { next ->
      Text(
        "${next.title} ${buttonFor(Action.NextShelf)}",
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
private fun ShelfGrid(state: AppState, shown: List<Rom>, index: Map<Char, Int>) {
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
      scope?.focus(gameKey(shown[target]))
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
    items(shown, key = { it.id }) { rom ->
      val key = gameKey(rom)
      LibraryCover(
        rom = rom,
        deviceState = state.deviceStates[rom.id],
        modifier =
          Modifier.aspectRatio(3f / 4f)
            .testTag(key)
            .onFocusChanged { if (it.isFocused) state.focused = Focused.OnGame(rom) }
            .focusItem(key, onConfirm = { state.navigator.push(Screen.Game(rom.id, rom.title)) }),
      )
    }
  }
}

private fun gameKey(rom: Rom) = "game:${rom.id}"

/** What a screen reader says for a game's [DeviceState]. */
private fun deviceStateText(state: DeviceState?) =
  when (state) {
    DeviceState.OnDevice -> "On this device"
    is DeviceState.Downloading -> "Downloading"
    null -> "Not on this device"
  }

/** A game's cover, marked when the game is on the device or on its way. */
@Composable
private fun LibraryCover(rom: Rom, deviceState: DeviceState?, modifier: Modifier) {
  Box(
    modifier.semantics(mergeDescendants = true) {
      role = Role.Button
      stateDescription = deviceStateText(deviceState)
    },
  ) {
    Cover(rom.coverSmallUrl, rom.title, Modifier.fillMaxSize())
    when (deviceState) {
      DeviceState.OnDevice ->
        Box(
          Modifier.align(Alignment.TopEnd)
            .padding(8.dp)
            .size(12.dp)
            .background(Placeholder.text, CircleShape)
            .testTag("onDevice:${rom.id}"),
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
 * The menu on an open shelf: sort, filter and the A–Z jump, the same choices the lower screen
 * offers to touch. Each choice closes the menu.
 */
@Composable
private fun ShelfMenu(
  state: AppState,
  title: String,
  letters: List<Char>,
  onDismissRequest: () -> Unit,
) {
  val view = state.libraryView
  fun choose(action: () -> Unit) {
    action()
    onDismissRequest()
  }
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    // It scrolls with focus, so a short screen still reaches the letters.
    Column(
      Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(title, color = Placeholder.text)
      for (sort in LibrarySort.entries.filter { it != view.sort }) {
        MenuButton("menu:sort:${sort.name}", "Sort by ${sort.title.lowercase()}") {
          choose { view.sort = sort }
        }
      }
      for (filter in LibraryFilter.entries.filter { it != view.filter }) {
        val label =
          when (filter) {
            LibraryFilter.All -> "Show all games"
            LibraryFilter.OnDevice -> "Show only games on this device"
            LibraryFilter.NotOnDevice -> "Show only games not on this device"
          }
        MenuButton("menu:filter:${filter.name}", label) { choose { view.filter = filter } }
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

/** The menu on the list of shelves: it shows or hides "All games". */
@Composable
private fun ListMenu(state: AppState, onDismissRequest: () -> Unit) {
  val allGames = state.settings.library.allGames
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(Place.Library.title, color = Placeholder.text)
      MenuButton("menu:allGames", if (allGames) "Hide “All games”" else "Show “All games”") {
        setAllGames(state, !allGames)
        onDismissRequest()
      }
    }
  }
}

/** Turns the "All games" shelf, first on the list, on or off. */
private fun setAllGames(state: AppState, on: Boolean) {
  state.settings = state.settings.copy(library = LibrarySettings(allGames = on))
}

/**
 * Library's lower screen. On the list, the focused shelf and a touch shortcut for "All games". On
 * an open shelf, the focused game and touch shortcuts for the filter, the sort and the A–Z jump.
 * The menus on the upper screen offer the same.
 */
@Composable
internal fun LibrarySecondary(state: AppState) {
  if (state.libraryView.isOpen) ShelfGamesSecondary(state) else ShelfListSecondary(state)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfListSecondary(state: AppState) {
  val cache = state.library
  val allGames = state.settings.library.allGames
  val platforms by remember(cache) { cache.platforms() }.collectAsState(emptyList())
  val shelves = remember(platforms, allGames) { libraryShelves(platforms, allGames) }
  val summary =
    rememberShelfSummaries(state, shelves).let { summaries ->
      val shelf = shelves.shelfAt(state.libraryView.shelfKey)
      summaries.find { it.shelf == shelf }
    }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (summary != null) {
        Text(summary.shelf.title, color = Placeholder.text, fontSize = 24.sp)
        Text(gameCount(summary.games), color = Placeholder.mutedText)
        Text("${summary.onDevice} on this device", color = Placeholder.mutedText)
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TouchChip("touch:allGames", "All games", allGames) { setAllGames(state, !allGames) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfGamesSecondary(state: AppState) {
  val view = state.libraryView
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.fillMaxWidth().weight(1f)) {
      (state.focused as? Focused.OnGame)?.let { GameDescription(state, it.rom) }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for (filter in LibraryFilter.entries) {
        TouchChip("touch:filter:${filter.name}", filter.title, view.filter == filter) {
          view.filter = filter
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
