package dev.sargunv.handheld.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.GameDescription
import dev.sargunv.handheld.ui.places.ShelfDescription

/**
 * Library's lower screen. On a tab of platforms or collections, the focused one. On a grid of
 * games, the focused game and touch shortcuts for the filter, the sort and the A–Z jump, which the
 * menu on the upper screen offers too.
 */
@Composable
internal fun LibrarySecondary(state: AppState) {
  when (val source = state.libraryView.source) {
    null -> ShelfListSecondary(state)
    else -> GamesSecondary(state, source.filters)
  }
}

@Composable
private fun ShelfListSecondary(state: AppState) {
  val view = state.libraryView
  val summary = view.summaries(state.libraryView.tab).find { it.shelf == view.shelf }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (summary != null) {
      val about = (summary.shelf as? LibraryShelf.OfCollection)?.collection?.description
      ShelfDescription(summary.shelf.title, summary.games, summary.onDevice, about)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamesSecondary(state: AppState, filters: Boolean) {
  val view = state.libraryView
  val letters = view.grid?.index?.keys.orEmpty()
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
    if (letters.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        for (letter in letters) {
          TouchChip("touch:jump:$letter", letter.toString(), selected = false) {
            view.jumpTo(letter)
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
