package dev.sargunv.handheld.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.FocusLazyVerticalGrid
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.LocalInputSettings
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.emptyLabel
import dev.sargunv.handheld.ui.places.gameCount
import dev.sargunv.handheld.ui.places.onDeviceCount

/**
 * The tabs, with the player's showing: the grid of games from [source], or without one, the tab's
 * shelves. L and R switch to the neighboring tab, stopping at the ends.
 */
@Composable
internal fun LibraryTabs(state: AppState, source: GridSource?, gridStates: SaveableStateHolder) {
  val tab = state.libraryTab
  val sync by state.romm.sync.collectAsState()
  fun switchTo(next: LibraryTab?) {
    if (next != null) state.libraryTab = next
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
        if (source != null) {
          GameGridPane(
            state = state,
            gridStates = gridStates,
            source = source,
            title = tab.title,
            empty = if (tab == LibraryTab.OnDevice) "No games" else sync.emptyLabel("No games"),
            backHint = "Home",
          )
        } else {
          ShelfList(state, tab)
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
internal fun buttonLabel(action: Action): String {
  val settings = LocalInputSettings.current
  return PadButton.entries.firstOrNull { settings.actionFor(it) == action }?.label.orEmpty()
}

/**
 * The platforms or collections of [tab]. Confirm opens the focused one, and the menu offers Open
 * with for its games.
 */
@Composable
private fun ShelfList(state: AppState, tab: LibraryTab) {
  val view = state.libraryView
  val sync by state.romm.sync.collectAsState()
  val shelves = view.shelves(tab)
  val focused = view.shelf
  val summaries = view.summaries(tab)
  var menuFor by remember { mutableStateOf<String?>(null) }
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
            sync.emptyLabel(
              if (tab == LibraryTab.Collections) "No collections" else "No platforms",
            ),
            color = Placeholder.mutedText,
            modifier = Modifier.align(Alignment.Center),
          )
        }
        if (hasShelves) {
          ShelfTiles(
            summaries = summaries,
            focused = focused,
            onFocus = { view.focusShelf(tab, it.key) },
            onOpen = { view.open(tab, it.key, OpenedFrom.Tab) },
          )
        }
      }
      HintBar(
        buildList {
          if (hasShelves) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Back, "Home"))
          if (hasShelves) add(Hint(Action.Menu, "Menu"))
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
            Text(onDeviceCount(summary.onDevice), color = Placeholder.mutedText, fontSize = 14.sp)
          }
        }
      }
    }
  }
}

private fun shelfTileKey(shelf: LibraryShelf) = "library:${shelf.key}"
