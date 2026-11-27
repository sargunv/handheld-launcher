package dev.sargunv.handheld.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.launchId
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen

/**
 * The menu on a platform or a collection: Open with, for all of its games. A collection's games
 * open by platform, so it offers Open with for each platform among them.
 */
@Composable
internal fun ShelfMenu(state: AppState, shelf: LibraryShelf, onDismissRequest: () -> Unit) {
  val choices: List<Pair<String, OpenWithScope>>? =
    when (shelf) {
      is LibraryShelf.OfPlatform ->
        listOf("Open with" to OpenWithScope.Platform(PlatformSlug(shelf.platform.slug)))
      is LibraryShelf.OfCollection -> {
        val among = state.libraryView.platformsOf(shelf.collection)
        among?.map { platform ->
          val label = if (among.size == 1) "Open with" else "Open ${platform.name} games with"
          label to OpenWithScope.Collection(shelf.collection.launchId, PlatformSlug(platform.slug))
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
        Text("No games", color = Placeholder.mutedText)
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
 * The menu on a grid of games: sort, filter when [filters] allows one, and the A–Z jump, the same
 * choices the lower screen offers to touch. Each choice closes the menu.
 */
@Composable
internal fun GamesMenu(
  state: AppState,
  title: String,
  filters: Boolean,
  onDismissRequest: () -> Unit,
) {
  val view = state.libraryView
  val letters = view.grid?.index?.keys.orEmpty()
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
          items(letters.toList()) { letter ->
            MenuButton("menu:jump:$letter", letter.toString()) {
              choose { view.jumpTo(letter) }
            }
          }
        }
      }
    }
  }
}
