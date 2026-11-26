package dev.sargunv.handheld.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.focus.FocusLazyVerticalGrid
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.NOT_ON_SERVER
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.gameCount
import kotlinx.coroutines.flow.map

/**
 * The games of [shelf], open from its tab. L and R step between shelves, stopping at the ends, and
 * Back returns to the tab, or Home when Home opened it.
 */
@Composable
internal fun ShelfGames(
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
internal fun GameGridPane(
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
