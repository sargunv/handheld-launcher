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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator

/** The demo's screens. */
sealed interface DemoScreen {
  data object Home : DemoScreen

  data class Detail(val tileId: Long) : DemoScreen
}

/** State the demo's two screens share. */
@Stable
class DemoState(val inputSettings: InputSettings = InputSettings()) {
  val navigator = Navigator<DemoScreen>(DemoScreen.Home)
  var focusedTile: Tile? by mutableStateOf(null)
}

/** The demo's top screen: shelves of tiles, a detail screen, and a dialog. */
@Composable
fun DemoPrimary(state: DemoState) {
  NavigationHost(state.navigator, Modifier.fillMaxSize().background(Placeholder.background)) {
    screen ->
    when (screen) {
      DemoScreen.Home -> NavEntry(screen) { HomeScreen(state) }
      is DemoScreen.Detail -> NavEntry(screen) { DetailScreen(screen.tileId) }
    }
  }
}

/** The demo's bottom screen: the focused tile, and a touch shortcut. */
@Composable
fun DemoSecondary(state: DemoState) {
  Column(
    Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Secondary screen", color = Placeholder.mutedText)
    Text(state.focusedTile?.title ?: "Nothing focused", color = Placeholder.text)
    TouchShortcut("Home") { while (state.navigator.back()) Unit }
  }
}

@Composable
private fun HomeScreen(state: DemoState) {
  var menuTileId by rememberSaveable { mutableStateOf<Long?>(null) }
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
          onFocusTile = { state.focusedTile = it },
          onOpenTile = { state.navigator.push(DemoScreen.Detail(it.id)) },
          onTileMenu = { menuTileId = it.id },
        )
      }
    }
    menuTileId?.let(DemoLibrary::tile)?.let { tile ->
      TileMenu(tile, onDismissRequest = { menuTileId = null })
    }
  }
}

@Composable
private fun ShelfRow(
  shelf: Shelf,
  onFocusTile: (Tile) -> Unit,
  onOpenTile: (Tile) -> Unit,
  onTileMenu: (Tile) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(shelf.title, color = Placeholder.mutedText, modifier = Modifier.padding(start = 48.dp))
    FocusLazyRow(
      modifier = Modifier.testTag("shelf:${shelf.id}"),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(shelf.tiles, key = { it.id }) { tile ->
        TileBox(
          tile = tile,
          modifier =
            Modifier.testTag(tileKey(tile))
              .onFocusChanged { if (it.isFocused) onFocusTile(tile) }
              .focusItem(
                key = tileKey(tile),
                onConfirm = { onOpenTile(tile) },
                onAction = { action ->
                  if (action == Action.Menu) {
                    onTileMenu(tile)
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
private fun TileBox(tile: Tile, modifier: Modifier) {
  Box(
    modifier.size(width = 120.dp, height = 160.dp).background(Placeholder.tile).padding(8.dp),
    contentAlignment = Alignment.BottomStart,
  ) {
    Text(tile.title, color = Placeholder.text)
  }
}

@Composable
private fun DetailScreen(tileId: Long) {
  val tile = DemoLibrary.tile(tileId) ?: return
  var showOptions by rememberSaveable { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.size(width = 180.dp, height = 240.dp).background(Placeholder.tile))
      Text(tile.title, color = Placeholder.text)
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DemoButton("play", "Play") {}
        DemoButton("options", "Options") { showOptions = true }
      }
    }
    if (showOptions) TileMenu(tile, onDismissRequest = { showOptions = false })
  }
}

@Composable
private fun TileMenu(tile: Tile, onDismissRequest: () -> Unit) {
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(tile.title, color = Placeholder.text)
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

internal fun tileKey(tile: Tile) = "tile:${tile.id}"
