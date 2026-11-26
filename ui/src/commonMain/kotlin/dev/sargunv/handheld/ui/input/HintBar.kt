package dev.sargunv.handheld.ui.input

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder

/** A button hint: what the button for [action] does on this screen. */
@Immutable data class Hint(val action: Action, val label: String)

/**
 * The button hints along the bottom edge of the upper screen. Each screen places one and fills it
 * with what its buttons do there. Each hint names the button that performs its action under the
 * player's input settings.
 */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
  val settings = LocalInputSettings.current
  Row(
    modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(32.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (hint in hints) {
      val button = PadButton.entries.firstOrNull { settings.actionFor(it) == hint.action }
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (button != null) {
          Text(
            button.label,
            color = Placeholder.text,
            fontSize = 12.sp,
            modifier =
              Modifier.border(1.dp, Placeholder.mutedText, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
        Text(hint.label, color = Placeholder.mutedText, fontSize = 14.sp)
      }
    }
  }
}

/** The name printed on the button, in the Xbox layout's names that Android reports. */
internal val PadButton.label: String
  get() =
    when (this) {
      PadButton.South -> "A"
      PadButton.East -> "B"
      PadButton.West -> "X"
      PadButton.North -> "Y"
      PadButton.L1 -> "L"
      PadButton.R1 -> "R"
      PadButton.L2 -> "L2"
      PadButton.R2 -> "R2"
      PadButton.ThumbLeft -> "L3"
      PadButton.ThumbRight -> "R3"
      PadButton.Start -> "Start"
      PadButton.Select -> "Select"
      PadButton.DpadUp -> "Up"
      PadButton.DpadDown -> "Down"
      PadButton.DpadLeft -> "Left"
      PadButton.DpadRight -> "Right"
    }
