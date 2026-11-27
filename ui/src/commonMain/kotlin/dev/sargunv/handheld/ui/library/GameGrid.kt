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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.RomStatus
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
import dev.sargunv.handheld.ui.places.describes
import dev.sargunv.handheld.ui.places.gameCount
import dev.sargunv.handheld.ui.places.onDeviceCount
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The games of [source]'s shelf, open from its tab. L and R step between shelves, stopping at the
 * ends, and Back returns to the tab, or Home when Home opened it.
 */
@Composable
internal fun ShelfGames(
  state: AppState,
  source: GridSource.OfShelf,
  gridStates: SaveableStateHolder,
) {
  val view = state.libraryView
  val tab = state.libraryTab
  val shelves = view.shelves(tab).orEmpty()
  val shelf = source.shelf
  val shelfKey = shelf.key

  fun moveTo(next: LibraryShelf?) {
    if (next != null) view.focusShelf(tab, next.key)
  }

  fun close() {
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
      source = source,
      title = shelf.title,
      empty = "No games",
      backHint = if (view.openedFrom == OpenedFrom.Home) "Home" else "Back",
    ) { grid ->
      ShelfHeader(shelves, shelf, grid)
    }
  }
}

/**
 * The grid of games from [source], with the sort, the filter when the source has one, and the A–Z
 * jump, which the menu and the lower screen change. Each source keeps its own focus for each sort
 * and filter. The grid says [empty] when the source has no games, and [header] shows above it with
 * them.
 */
@Composable
internal fun GameGridPane(
  state: AppState,
  gridStates: SaveableStateHolder,
  source: GridSource,
  title: String,
  empty: String,
  backHint: String,
  header: @Composable (grid: GridGames?) -> Unit = {},
) {
  val view = state.libraryView
  val grid = view.grid
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  // The menu sorts and filters, which a grid with no games at all has no use for.
  val hasMenu = !grid?.games.isNullOrEmpty()

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
      header(grid)
      Box(Modifier.fillMaxWidth().weight(1f)) {
        val filter = if (source.filters) view.filter else LibraryFilter.All
        val stateKey = "${source.key}/${view.sort}/$filter"
        gridStates.SaveableStateProvider(stateKey) {
          key(stateKey) {
            FocusScope(Modifier.fillMaxSize()) {
              val message =
                when {
                  grid == null -> null
                  grid.games.isEmpty() -> empty
                  grid.shown.isEmpty() -> "No matches"
                  else -> null
                }
              if (message != null) {
                Text(
                  message,
                  color = Placeholder.mutedText,
                  modifier = Modifier.align(Alignment.Center),
                )
              }
              if (grid != null && grid.shown.isNotEmpty()) GameGrid(state, grid)
            }
          }
        }
      }
      HintBar(
        buildList {
          if (state.focused is Focused.OnGame) add(Hint(Action.Confirm, "Open"))
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
        filters = source.filters,
        onDismissRequest = { menuOpen = false },
      )
    }
  }
}

@Composable
private fun ShelfHeader(shelves: List<LibraryShelf>, shelf: LibraryShelf, grid: GridGames?) {
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
    if (grid != null) {
      Text(
        "${gameCount(grid.games.size)}, ${onDeviceCount(grid.onDevice)}",
        color = Placeholder.mutedText,
      )
    }
  }
}

@Composable
private fun GameGrid(state: AppState, games: GridGames) {
  val view = state.libraryView
  val grid = rememberLazyGridState()
  val scope = LocalFocusScope.current
  val currentGames by rememberUpdatedState(games)
  LaunchedEffect(view, scope) {
    view.jumps.receiveAsFlow().collectLatest { letter ->
      val current = currentGames
      val target = current.index[letter] ?: return@collectLatest
      grid.scrollToItem(target)
      scope?.focus(gameTag(current.shown[target]))
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
    items(games.shown, key = { gameTag(it) }) { game ->
      val status = statusOf(game, games.statuses)
      LibraryCover(
        game = game,
        status = status,
        modifier =
          Modifier.aspectRatio(3f / 4f)
            .describes(state, Focused.OnGame(game))
            .focusItem(
              gameTag(game),
              onConfirm = { state.navigator.push(Screen.Game(game.key, game.title)) },
              stateDescription = statusText(status),
            ),
      )
    }
  }
}

/** What a screen reader says for a game's [status]. */
private fun statusText(status: RomStatus) =
  when (status) {
    is RomStatus.OnDevice -> "On this device"
    RomStatus.Queued,
    is RomStatus.Downloading -> "Downloading"
    is RomStatus.Failed,
    RomStatus.NotOnDevice -> "Not on this device"
  }

/**
 * A game's cover, marked when the game is on the device or on its way. A local game has no cover,
 * so its tile shows its title, and says it's not on the server.
 */
@Composable
private fun LibraryCover(game: LibraryGame, status: RomStatus, modifier: Modifier) {
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
    when (status) {
      is RomStatus.OnDevice ->
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
      RomStatus.Queued,
      is RomStatus.Downloading -> {
        val progress = (status as? RomStatus.Downloading)?.progress
        Box(
          Modifier.align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.Black.copy(alpha = 0.6f)),
        ) {
          Box(
            Modifier.fillMaxHeight()
              .fillMaxWidth(progress ?: 1f)
              .background(if (progress == null) Placeholder.mutedText else Placeholder.text),
          )
        }
      }
      is RomStatus.Failed,
      RomStatus.NotOnDevice -> Unit
    }
  }
}
