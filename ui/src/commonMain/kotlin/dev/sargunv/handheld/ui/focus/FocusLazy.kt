package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp

/** Directions that can't leave a list sideways: a row's ends are walls, not ways out. */
val HorizontalWalls: Set<FocusDirection> = setOf(FocusDirection.Left, FocusDirection.Right)

/**
 * A lazy row of [focusItem]s. Focus stays in view with room to spare, comes back to the item that
 * had it when it re-enters the row, and doesn't leave through the row's ends unless [walls] allows
 * it.
 */
@Composable
fun FocusLazyRow(
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  contentPadding: PaddingValues = PaddingValues(0.dp),
  horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
  verticalAlignment: Alignment.Vertical = Alignment.Top,
  walls: Set<FocusDirection> = HorizontalWalls,
  content: LazyListScope.() -> Unit,
) {
  FocusList(walls) { listModifier ->
    LazyRow(
      modifier = modifier.then(listModifier),
      state = state,
      contentPadding = contentPadding,
      horizontalArrangement = horizontalArrangement,
      verticalAlignment = verticalAlignment,
      content = content,
    )
  }
}

/**
 * A lazy column of [focusItem]s or [FocusLazyRow]s. Focus stays in view with room to spare and
 * comes back to the item that had it when it re-enters the column. By default focus can leave
 * through either end, toward whatever sits above or below.
 */
@Composable
fun FocusLazyColumn(
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  contentPadding: PaddingValues = PaddingValues(0.dp),
  verticalArrangement: Arrangement.Vertical = Arrangement.Top,
  horizontalAlignment: Alignment.Horizontal = Alignment.Start,
  walls: Set<FocusDirection> = emptySet(),
  content: LazyListScope.() -> Unit,
) {
  FocusList(walls) { listModifier ->
    LazyColumn(
      modifier = modifier.then(listModifier),
      state = state,
      contentPadding = contentPadding,
      verticalArrangement = verticalArrangement,
      horizontalAlignment = horizontalAlignment,
      content = content,
    )
  }
}

/**
 * A lazy vertical grid of [focusItem]s. Focus stays in view with room to spare and comes back to
 * the item that had it when it re-enters the grid. Like a row, its sides are walls by default;
 * focus can leave through the top and bottom. Focus doesn't wrap from the end of one line to the
 * next.
 */
@Composable
fun FocusLazyVerticalGrid(
  columns: GridCells,
  modifier: Modifier = Modifier,
  state: LazyGridState = rememberLazyGridState(),
  contentPadding: PaddingValues = PaddingValues(0.dp),
  verticalArrangement: Arrangement.Vertical = Arrangement.Top,
  horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
  walls: Set<FocusDirection> = HorizontalWalls,
  content: LazyGridScope.() -> Unit,
) {
  FocusList(walls) { listModifier ->
    LazyVerticalGrid(
      columns = columns,
      modifier = modifier.then(listModifier),
      state = state,
      contentPadding = contentPadding,
      verticalArrangement = verticalArrangement,
      horizontalArrangement = horizontalArrangement,
      content = content,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusList(walls: Set<FocusDirection>, list: @Composable (Modifier) -> Unit) {
  val saved = rememberSaveable { mutableStateOf<String?>(null) }
  val scope = LocalFocusScope.current
  val memory = remember(saved, scope) { FocusMemory(saved) { scope?.isRecording ?: true } }
  val listModifier =
    Modifier.focusProperties {
        onEnter = {
          // Moving in from a neighbor returns to the remembered item. A request for a specific
          // item (a tap, or a scope restoring focus) goes where it asked.
          if (requestedFocusDirection != FocusDirection.Enter) memory.focusRemembered()
        }
        onExit = { if (requestedFocusDirection in walls) cancelFocusChange() }
      }
      .focusGroup()
  CompositionLocalProvider(
    LocalFocusMemory provides memory,
    LocalBringIntoViewSpec provides ComfortableBringIntoViewSpec,
  ) {
    list(listModifier)
  }
}

/**
 * Scrolls a focused item into view with a margin of a fifth of the viewport on each side, so the
 * neighbors it leads to are already showing. The list's own bounds still apply, so the first and
 * last items sit at the ends.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object ComfortableBringIntoViewSpec : BringIntoViewSpec {
  private const val MARGIN_FRACTION = 0.2f

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val margin =
      minOf(containerSize * MARGIN_FRACTION, ((containerSize - size) / 2).coerceAtLeast(0f))
    val leading = offset - margin
    val trailing = offset + size - (containerSize - margin)
    return when {
      leading < 0 -> leading
      trailing > 0 -> trailing
      else -> 0f
    }
  }
}
