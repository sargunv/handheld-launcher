package dev.sargunv.handheld.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/** The screens the player came through to reach the current one, oldest first. */
@Stable
class Navigator<T : Any>(start: T) {
  val backStack: SnapshotStateList<T> = mutableStateListOf(start)

  val current: T
    get() = backStack.last()

  fun push(screen: T) {
    backStack.add(screen)
  }

  /** Leaves the current screen. Returns false at the first screen, which stays. */
  fun back(): Boolean {
    if (backStack.size <= 1) return false
    backStack.removeAt(backStack.lastIndex)
    return true
  }

  /** Leaves every screen but the first. */
  fun backToStart() {
    while (back()) continue
  }
}

/**
 * Shows the [navigator]'s current screen. Each screen is a [FocusScope], so returning to a screen
 * puts focus back on the item that opened the next one. [Action.Back] and the system back gesture
 * both leave the current screen. Screens change instantly, without a transition.
 */
@Composable
fun <T : Any> NavigationHost(
  navigator: Navigator<T>,
  modifier: Modifier = Modifier,
  entryProvider: (T) -> NavEntry<T>,
) {
  val focusScopeDecorator = remember {
    NavEntryDecorator<T> { entry -> FocusScope(Modifier.fillMaxSize()) { entry.Content() } }
  }
  NavDisplay(
    backStack = navigator.backStack,
    modifier =
      modifier.onAction { action ->
        if (action != Action.Back) return@onAction false
        // Back on the first screen does nothing, rather than leaving the app.
        navigator.back()
        true
      },
    onBack = { navigator.back() },
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), focusScopeDecorator),
    transitionSpec = { Instant },
    popTransitionSpec = { Instant },
    predictivePopTransitionSpec = { Instant },
    entryProvider = entryProvider,
  )
}

private val Instant = ContentTransform(EnterTransition.None, ExitTransition.None)
