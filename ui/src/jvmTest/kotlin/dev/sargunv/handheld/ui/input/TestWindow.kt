package dev.sargunv.handheld.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.sargunv.handheld.data.settings.InputSettings

/**
 * Stands in for a window that keeps [heldKeys] up to date. Unlike a window, it only sees keys while
 * something below it has focus, which is enough for tests that keep focus.
 */
fun Modifier.trackHeldKeys(heldKeys: HeldKeys): Modifier = onPreviewKeyEvent {
  heldKeys.track(it)
  false
}

/** An [ActionInputRoot] whose held keys [trackHeldKeys] keeps up to date. */
@Composable
fun TestActionInputRoot(
  settings: InputSettings = InputSettings(),
  content: @Composable () -> Unit,
) {
  val heldKeys = remember { HeldKeys() }
  ActionInputRoot(settings, heldKeys, Modifier.trackHeldKeys(heldKeys), content)
}
