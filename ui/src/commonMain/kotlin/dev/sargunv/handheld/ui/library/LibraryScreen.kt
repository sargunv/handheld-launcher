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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A request to jump to the first game under [letter]. Each request is a new one. */
class Jump(val letter: Char)

/** What Library shows, which its upper and lower screens share. */
@Stable
class LibraryState {
  /** The key of the shelf on screen, or null for the first. */
  var shelfKey: String? by mutableStateOf(null)
  var filter: LibraryFilter by mutableStateOf(LibraryFilter.All)
  var sort: LibrarySort by mutableStateOf(LibrarySort.Name)

  /** The letters the shown games can jump to, which the upper screen keeps up to date. */
  var letters: List<Char> by mutableStateOf(emptyList())
    internal set

  /** A jump that the shown shelf hasn't carried out yet. */
  var jump: Jump? by mutableStateOf(null)
}

/**
 * Library: one shelf at a time, a platform or the whole library, as a grid of covers from the
 * library cache. L and R step between shelves; past the first or last they go on to the neighboring
 * place.
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
  val shelfKey = shelf?.key
  // Each value carries its shelf's key, so a shelf never shows the games of the one before it.
  val loaded by
    remember(cache, shelf) {
        when (shelf) {
          null -> flowOf(emptyList())
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
  val shelfStates = rememberSaveableStateHolder()

  fun moveTo(next: LibraryShelf) {
    view.shelfKey = next.key
    state.focused = null
  }

  Box(
    Modifier.fillMaxSize().onAction { action ->
      when (action) {
        // Past either end, the action goes on to switch places.
        Action.PreviousShelf -> moveTo(shelves.step(shelfKey, -1) ?: return@onAction false)
        Action.NextShelf -> moveTo(shelves.step(shelfKey, 1) ?: return@onAction false)
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
        // Each shelf, sort and filter keeps its own focus and scroll position, so focus comes back
        // where it was on each.
        val stateKey = "${shelfKey}/${view.sort}/${view.filter}"
        shelfStates.SaveableStateProvider(stateKey) {
          key(stateKey) {
            FocusScope(Modifier.fillMaxSize()) {
              val message =
                when {
                  platforms == null || (shelf != null && shown == null) -> null
                  shelf == null ->
                    when (state.sync) {
                      SyncStatus.Failed -> "Couldn't reach your server to sync your library."
                      SyncStatus.Synced -> "Your library has no games yet."
                      else -> "Syncing your library"
                    }
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
                ShelfGrid(state, shown, index)
              }
            }
          }
        }
      }
      HintBar(
        buildList {
          if (state.focused is Focused.OnGame) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Menu, "Menu"))
          add(Hint(Action.QuickMenu, "Quick menu"))
        },
      )
    }
    if (menuOpen) {
      LibraryMenu(
        state = state,
        title = shelf?.title ?: Place.Library.title,
        letters = index.keys.toList(),
        onDismissRequest = { menuOpen = false },
      )
    }
  }
}

@Composable
private fun ShelfHeader(
  shelves: List<LibraryShelf>,
  shelf: LibraryShelf?,
  games: Int?,
  onDevice: Int?,
) {
  val settings = LocalInputSettings.current
  fun buttonFor(action: Action) =
    PadButton.entries.firstOrNull { settings.actionFor(it) == action }?.label.orEmpty()
  val at = shelves.indexOf(shelf)
  // Past the ends, L and R lead to the neighboring places.
  val previous = shelves.getOrNull(at - 1)?.title ?: Place.Home.title
  val next = shelves.getOrNull(at + 1)?.title ?: Place.Downloads.title
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (shelf != null) {
      Text(
        "${buttonFor(Action.PreviousShelf)} $previous",
        color = Placeholder.mutedText,
        fontSize = 14.sp,
      )
      Text(
        shelf.title,
        color = Placeholder.text,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("shelf:${shelf.key}"),
      )
      Text(
        "$next ${buttonFor(Action.NextShelf)}",
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

/** A game's cover, marked when the game is on the device or on its way. */
@Composable
private fun LibraryCover(rom: Rom, deviceState: DeviceState?, modifier: Modifier) {
  Box(modifier) {
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
 * The menu on Library: sort, filter, the A–Z jump and the "All games" shelf, the same choices the
 * lower screen offers to touch. Each choice closes the menu.
 */
@Composable
private fun LibraryMenu(
  state: AppState,
  title: String,
  letters: List<Char>,
  onDismissRequest: () -> Unit,
) {
  val view = state.libraryView
  val settings = state.settings.library
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
      val allGames = if (settings.allGames) "Hide “All games”" else "Show “All games”"
      MenuButton("menu:allGames", allGames) {
        choose { setAllGames(state, !settings.allGames) }
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

/** Turns the "All games" shelf on, and goes to it, or off. */
private fun setAllGames(state: AppState, on: Boolean) {
  state.settings = state.settings.copy(library = LibrarySettings(allGames = on))
  if (on) {
    state.libraryView.shelfKey = LibraryShelf.AllGames.key
    state.focused = null
  }
}

/**
 * Library's lower screen: the focused game, and touch shortcuts for the filter, the sort and the
 * A–Z jump. The menu on the upper screen offers the same.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySecondary(state: AppState) {
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
      TouchChip("touch:allGames", "All games", state.settings.library.allGames) {
        setAllGames(state, !state.settings.library.allGames)
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
      .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
      .background(if (selected) Placeholder.text else Placeholder.tile)
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Text(label, color = if (selected) Placeholder.background else Placeholder.text)
  }
}
