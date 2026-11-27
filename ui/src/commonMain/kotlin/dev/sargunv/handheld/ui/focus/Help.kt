package dev.sargunv.handheld.ui.focus

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/** What a lower screen says about the focused item. */
@Stable
internal class HelpHolder {
  var shown: Help? by mutableStateOf(null)
}

/** The [text] a lower screen shows under the [label] of the item with [key]. */
@Immutable
internal data class Help(
  val key: String,
  val label: String,
  val text: String,
  val hasMenu: Boolean,
)

/**
 * Shows [help] under [label] on [holder]'s lower screen when the item with [key] takes focus, or
 * nothing when [help] is null. The help stays once focus leaves, but the menu that [hasMenu] offers
 * doesn't.
 */
internal fun Modifier.help(
  holder: HelpHolder,
  key: String,
  label: String,
  help: String?,
  hasMenu: Boolean = false,
): Modifier = onFocusChanged {
  val shown = holder.shown
  when {
    it.hasFocus -> holder.shown = help?.let { text -> Help(key, label, text, hasMenu) }
    hasMenu && shown?.key == key -> holder.shown = shown.copy(hasMenu = false)
  }
}
