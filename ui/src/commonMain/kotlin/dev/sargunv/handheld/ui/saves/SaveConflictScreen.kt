package dev.sargunv.handheld.ui.saves

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One copy of a save in a conflict: when it last changed, and its size. */
@Immutable data class SaveVersion(val changedAt: Instant, val sizeBytes: Long)

/** A game whose save changed both on this device and on the server since the last sync. */
@Immutable
data class SaveConflict(val gameName: String, val device: SaveVersion, val server: SaveVersion) {
  /** The copy that changed last. */
  val newer: SaveCopy
    get() = if (device.changedAt > server.changedAt) SaveCopy.Device else SaveCopy.Server
}

/**
 * Asks which copy of a save to keep, before the game launches. The newer copy has focus, and one
 * press chooses. The other copy goes to the game's save history, which the screen says.
 *
 * @param onChoose called with the copy to keep.
 * @param onCancel called on [Action.Back], to leave both copies as they are and not launch.
 */
@Composable
fun SaveConflictScreen(
  conflict: SaveConflict,
  onChoose: (SaveCopy) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  now: Instant = Clock.System.now(),
) {
  FocusScope(
    modifier =
      modifier.fillMaxSize().background(Palette.background).onAction { action ->
        if (action == Action.Back) {
          onCancel()
          true
        } else {
          false
        }
      },
    initialFocus = conflict.newer.name,
  ) {
    Column(Modifier.fillMaxSize()) {
      Column(
        Modifier.fillMaxWidth().weight(1f).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        Text(
          "Choose a save · ${conflict.gameName}",
          color = Palette.text,
          fontSize = 28.sp,
          modifier = Modifier.semantics { heading() },
        )
        Text(
          "Changed on both since the last sync · The other copy goes to save history",
          color = Palette.mutedText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
          for (copy in listOf(SaveCopy.Device, SaveCopy.Server)) {
            val version = if (copy == SaveCopy.Device) conflict.device else conflict.server
            Column(
              Modifier.width(280.dp)
                .focusItem(copy.name, onConfirm = { onChoose(copy) })
                .background(Palette.tile)
                .padding(24.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                if (copy == SaveCopy.Device) "This device" else "RomM",
                color = Palette.text,
                fontSize = 22.sp,
              )
              Text("Saved ${ago(version.changedAt, now)}", color = Palette.text)
              Text(formatSize(version.sizeBytes), color = Palette.mutedText)
              if (copy == conflict.newer) Text("Newer", color = Palette.mutedText)
            }
          }
        }
      }
      // It shows during a launch, which the main menu waits for.
      HintBar(listOf(Hint(Action.Confirm, "Play"), Hint(Action.Back, "Cancel")), mainMenu = false)
    }
  }
}

/** [time] relative to [now], in words, such as "5 minutes ago". */
internal fun ago(time: Instant, now: Instant): String {
  val elapsed = now - time
  fun count(value: Long, unit: String) = "$value $unit${if (value == 1L) "" else "s"} ago"
  return when {
    elapsed < 1.minutes -> "just now"
    elapsed < 1.hours -> count(elapsed.inWholeMinutes, "minute")
    elapsed < 1.days -> count(elapsed.inWholeHours, "hour")
    else -> count(elapsed.inWholeDays, "day")
  }
}
