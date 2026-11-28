package dev.sargunv.handheld.ui.saves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.places.MenuButton

/**
 * A game whose newer save on RomM can't come to the device yet, because [emulatorName] may still be
 * running with the game, and would write the save it holds in memory over the new one. [canClose]
 * is set when the launcher can close the emulator.
 */
@Immutable
data class StillRunning(val gameName: String, val emulatorName: String, val canClose: Boolean)

/**
 * Asks what to do before a launch when [prompt]'s emulator may still hold the game: quit it from
 * its own menu first, which cancels the launch, play with this device's save, or close it now.
 * [Action.Back][dev.sargunv.handheld.ui.input.Action.Back] cancels the launch too.
 */
@Composable
fun StillRunningDialog(prompt: StillRunning, onChoose: (StillRunningChoice) -> Unit) {
  Dialog(
    onDismissRequest = { onChoose(StillRunningChoice.QuitFirst) },
    Modifier.widthIn(max = 640.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "${prompt.emulatorName} may still be running",
        color = Palette.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        "May overwrite RomM's newer save of ${prompt.gameName}",
        color = Palette.mutedText,
      )
      MenuButton("stillRunning:quitFirst", "Quit from ${prompt.emulatorName} first") {
        onChoose(StillRunningChoice.QuitFirst)
      }
      MenuButton("stillRunning:playDevice", "Play with this device's save") {
        onChoose(StillRunningChoice.PlayDeviceSave)
      }
      if (prompt.canClose) {
        MenuButton("stillRunning:closeNow", "Close ${prompt.emulatorName} now") {
          onChoose(StillRunningChoice.CloseNow)
        }
      }
    }
  }
}
