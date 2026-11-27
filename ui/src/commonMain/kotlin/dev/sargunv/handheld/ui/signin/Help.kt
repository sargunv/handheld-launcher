package dev.sargunv.handheld.ui.signin

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

/** The [text] a lower screen shows under an item's [label]. */
@Immutable internal data class Help(val label: String, val text: String, val hasMenu: Boolean)

/**
 * Shows [help] under [label] on [holder]'s lower screen when this item takes focus, or nothing when
 * [help] is null. The help stays once focus leaves, but the menu that [hasMenu] offers doesn't.
 */
internal fun Modifier.help(
  holder: HelpHolder,
  label: String,
  help: String?,
  hasMenu: Boolean = false,
): Modifier {
  val shown = help?.let { Help(label, it, hasMenu) }
  return onFocusChanged {
    when {
      it.hasFocus -> holder.shown = shown
      hasMenu && holder.shown == shown -> holder.shown = shown?.copy(hasMenu = false)
    }
  }
}
