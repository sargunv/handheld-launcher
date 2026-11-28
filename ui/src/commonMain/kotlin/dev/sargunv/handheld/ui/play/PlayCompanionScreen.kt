package dev.sargunv.handheld.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.focus.touchOnly
import kotlinx.coroutines.flow.StateFlow

/** The companion's Sync: not pressed yet, under way, or done with the line saying how it went. */
sealed interface CompanionSync {
  data object Idle : CompanionSync

  data object Syncing : CompanionSync

  data class Done(val message: String) : CompanionSync
}

/**
 * The lower screen while a game plays in another app: touch buttons to sync the save now and to go
 * back to the library. It takes no focus, so the controller stays with the game.
 *
 * @param sync how the save sync that [onSync] starts is going.
 * @param onQuit closes the emulator and goes back to the library, or null when the launcher can't.
 */
@Composable
fun PlayCompanionScreen(
  title: String,
  sync: StateFlow<CompanionSync>,
  onSync: () -> Unit,
  onBackToLibrary: () -> Unit,
  onQuit: (() -> Unit)? = null,
) {
  val state by sync.collectAsState()
  Column(
    Modifier.fillMaxSize().background(Palette.secondaryBackground).padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      title,
      color = Palette.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      TouchButton(
        "companion:sync",
        if (state == CompanionSync.Syncing) "Syncing" else "Sync",
        onSync,
      )
      TouchButton("companion:back", "Back to library", onBackToLibrary)
      onQuit?.let { TouchButton("companion:quit", "Quit", it) }
    }
    Text(
      (state as? CompanionSync.Done)?.message.orEmpty(),
      color = Palette.mutedText,
      modifier = Modifier.testTag("message").semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}

/** A button for touch only, which a screen reader can press too. It never takes focus. */
@Composable
private fun TouchButton(tag: String, label: String, onTap: () -> Unit) {
  Box(
    Modifier.testTag(tag)
      .touchOnly(onTap = onTap)
      .background(Palette.tile)
      .padding(horizontal = 32.dp, vertical = 20.dp),
  ) {
    Text(label, color = Palette.text, fontSize = 20.sp)
  }
}
