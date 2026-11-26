package dev.sargunv.handheld.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import kotlinx.coroutines.launch

/**
 * The lower screen while a game plays in another app: touch buttons to sync the save now and to go
 * back to the library. It takes no focus, so the controller stays with the game.
 *
 * @param sync syncs the game's save and returns what happened, in plain words.
 */
@Composable
fun PlayCompanionScreen(title: String, sync: suspend () -> String, onBackToLibrary: () -> Unit) {
  val scope = rememberCoroutineScope()
  var syncing by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  Column(
    Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      title,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      TouchButton("companion:sync", if (syncing) "Syncing" else "Sync") {
        if (syncing) return@TouchButton
        syncing = true
        message = null
        scope.launch {
          try {
            message = sync()
          } finally {
            syncing = false
          }
        }
      }
      TouchButton("companion:back", "Back to library", onBackToLibrary)
    }
    Text(
      message.orEmpty(),
      color = Placeholder.mutedText,
      modifier = Modifier.testTag("message").semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}

/** A button for touch only, which a screen reader can press too. It never takes focus. */
@Composable
private fun TouchButton(tag: String, label: String, onTap: () -> Unit) {
  val currentOnTap by rememberUpdatedState(onTap)
  Box(
    Modifier.testTag(tag)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        onClick {
          currentOnTap()
          true
        }
      }
      .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
      .background(Placeholder.tile)
      .padding(horizontal = 32.dp, vertical = 20.dp),
  ) {
    Text(label, color = Placeholder.text, fontSize = 20.sp)
  }
}
