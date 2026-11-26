package dev.sargunv.handheld.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/**
 * While [enabled], calls [onBack] on [Action.Back] from within [content] and on the system back
 * gesture.
 */
@Composable
fun BackHandler(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  if (LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      rememberNavigationEventState(NavigationEventInfo.None),
      isBackEnabled = enabled,
      onBackCompleted = onBack,
    )
  }
  Box(
    modifier.onAction { action ->
      val handled = enabled && action == Action.Back
      if (handled) onBack()
      handled
    },
    content = content,
  )
}
