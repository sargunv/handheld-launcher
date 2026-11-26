package dev.sargunv.handheld.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Game
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator

/** The demo's screens. */
sealed interface DemoScreen {
  data object Home : DemoScreen

  data class Detail(val gameId: Long) : DemoScreen
}

/** State the demo's two screens share. */
@Stable
class DemoState(settings: Settings = Settings()) {
  val navigator = Navigator<DemoScreen>(DemoScreen.Home)
  var focusedGame: Game? by mutableStateOf(null)
  var settings: Settings by mutableStateOf(settings)

  val inputSettings: InputSettings
    get() = settings.input

  fun swapConfirmButton() {
    val swapped =
      when (inputSettings.confirmButton) {
        ConfirmButton.South -> ConfirmButton.East
        ConfirmButton.East -> ConfirmButton.South
      }
    settings = settings.copy(input = inputSettings.copy(confirmButton = swapped))
  }
}

/** The demo's top screen: shelves of placeholder tiles, a detail screen, and a dialog. */
@Composable
fun DemoPrimary(state: DemoState) {
  NavigationHost(state.navigator, Modifier.fillMaxSize().background(Placeholder.background)) {
    screen ->
    when (screen) {
      DemoScreen.Home -> NavEntry(screen) { HomeScreen(state) }
      is DemoScreen.Detail -> NavEntry(screen) { DetailScreen(state, screen.gameId) }
    }
  }
}

/** The demo's bottom screen: context for the focused game, and touch shortcuts. */
@Composable
fun DemoSecondary(state: DemoState) {
  Column(
    Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Secondary screen", color = Placeholder.mutedText)
    Text(state.focusedGame?.title ?: "Nothing focused", color = Placeholder.text)
    Text("Confirm: ${state.inputSettings.confirmButton}", color = Placeholder.mutedText)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      TouchShortcut("Home") { while (state.navigator.back()) Unit }
      TouchShortcut("Swap confirm") { state.swapConfirmButton() }
    }
  }
}

@Composable
private fun HomeScreen(state: DemoState) {
  var menuGameId by rememberSaveable { mutableStateOf<Long?>(null) }
  val focusManager = LocalFocusManager.current
  Box(Modifier.fillMaxSize()) {
    FocusLazyColumn(
      modifier =
        Modifier.fillMaxSize().onAction { action ->
          val direction =
            when (action) {
              Action.PreviousShelf -> FocusDirection.Up
              Action.NextShelf -> FocusDirection.Down
              else -> return@onAction false
            }
          focusManager.moveFocus(direction)
          true
        },
      contentPadding = PaddingValues(vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      items(DemoLibrary.shelves, key = { it.id }) { shelf ->
        ShelfRow(
          shelf = shelf,
          onFocusGame = { state.focusedGame = it },
          onOpenGame = { state.navigator.push(DemoScreen.Detail(it.rommId)) },
          onGameMenu = { menuGameId = it.rommId },
        )
      }
    }
    menuGameId?.let(DemoLibrary::game)?.let { game ->
      GameMenu(game, onDismissRequest = { menuGameId = null })
    }
  }
}

@Composable
private fun ShelfRow(
  shelf: Shelf,
  onFocusGame: (Game) -> Unit,
  onOpenGame: (Game) -> Unit,
  onGameMenu: (Game) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(shelf.title, color = Placeholder.mutedText, modifier = Modifier.padding(start = 48.dp))
    FocusLazyRow(
      modifier = Modifier.testTag("shelf:${shelf.id}"),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(shelf.games, key = { it.rommId }) { game ->
        Tile(
          game = game,
          modifier =
            Modifier.testTag(gameKey(game))
              .onFocusChanged { if (it.isFocused) onFocusGame(game) }
              .focusItem(
                key = gameKey(game),
                onConfirm = { onOpenGame(game) },
                onAction = { action ->
                  if (action == Action.Menu) {
                    onGameMenu(game)
                    true
                  } else {
                    false
                  }
                },
              ),
        )
      }
    }
  }
}

@Composable
private fun Tile(game: Game, modifier: Modifier) {
  Box(
    modifier.size(width = 120.dp, height = 160.dp).background(Placeholder.tile).padding(8.dp),
    contentAlignment = Alignment.BottomStart,
  ) {
    Text(game.title, color = Placeholder.text)
  }
}

@Composable
private fun DetailScreen(state: DemoState, gameId: Long) {
  val game = DemoLibrary.game(gameId) ?: return
  var showOptions by rememberSaveable { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.size(width = 180.dp, height = 240.dp).background(Placeholder.tile))
      Text(game.title, color = Placeholder.text)
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DemoButton("play", "Play") {}
        DemoButton("options", "Options") { showOptions = true }
        DemoButton("confirm", "Confirm: ${state.inputSettings.confirmButton}") {
          state.swapConfirmButton()
        }
      }
    }
    if (showOptions) GameMenu(game, onDismissRequest = { showOptions = false })
  }
}

@Composable
private fun GameMenu(game: Game, onDismissRequest: () -> Unit) {
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(game.title, color = Placeholder.text)
      DemoButton("menu:first", "First option", onConfirm = onDismissRequest)
      DemoButton("menu:second", "Second option", onConfirm = onDismissRequest)
      DemoButton("menu:close", "Close", onConfirm = onDismissRequest)
    }
  }
}

@Composable
private fun DemoButton(key: String, label: String, onConfirm: () -> Unit) {
  Box(
    Modifier.testTag(key)
      .focusItem(key, onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}

@Composable
private fun TouchShortcut(label: String, onTap: () -> Unit) {
  Box(
    Modifier.background(Placeholder.tile)
      .pointerInput(onTap) { detectTapGestures { onTap() } }
      .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}

internal fun gameKey(game: Game) = "game:${game.rommId}"

/** Neutral stand-in colors until the design system exists. */
internal object Placeholder {
  val background = Color(0xFF2B2A28)
  val secondaryBackground = Color(0xFF242321)
  val tile = Color(0xFF55534F)
  val dialog = Color(0xFF3A3936)
  val text = Color(0xFFE8E4DC)
  val mutedText = Color(0xFFA9A49A)
}
