package dev.sargunv.handheld.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.HelpHolder
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.focus.help
import dev.sargunv.handheld.ui.input.Action

/**
 * A text field for a controller: an item that focus moves over like any other, and that opens the
 * system keyboard on [Action.Confirm] or a tap. [Action.Back], [Action.Confirm] or moving focus
 * away stops editing; the keyboard's action key also calls [onSubmit]. Focusing it puts [help], if
 * any, on [helpHolder]'s lower screen.
 *
 * @param label names the field to accessibility services and titles its help.
 * @param secure hides what's typed, for secrets.
 */
@Composable
internal fun TextEntry(
  helpHolder: HelpHolder,
  key: String,
  field: TextFieldState,
  placeholder: String,
  label: String,
  help: String?,
  keyboardOptions: KeyboardOptions,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
  secure: Boolean = false,
) {
  var editing by remember { mutableStateOf(false) }
  val itemRequester = remember { FocusRequester() }
  val fieldRequester = remember { FocusRequester() }
  fun stopEditing() {
    itemRequester.requestFocus()
    editing = false
  }
  val text = field.text
  // Shown text that a screen reader would read as dots, or as a placeholder, says nothing; the
  // label names the field and says what it holds.
  val value =
    when {
      text.isEmpty() -> "empty"
      secure -> "entered"
      else -> text.toString()
    }
  Box(
    modifier
      .help(helpHolder, key, label, help)
      .focusRequester(itemRequester)
      .focusItem(
        key,
        onConfirm = { if (editing) stopEditing() else editing = true },
        description = if (editing) null else "$label: $value",
        onAction = { action ->
          if (action == Action.Back && editing) {
            stopEditing()
            true
          } else {
            false
          }
        },
      )
      .background(Placeholder.tile)
      .then(if (editing) Modifier.border(2.dp, Placeholder.text) else Modifier)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    if (editing) {
      var hadFocus by remember { mutableStateOf(false) }
      val modifier =
        Modifier.fillMaxWidth().focusRequester(fieldRequester).onFocusChanged {
          // Focus that moves on, such as with the D-pad, ends editing too.
          if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
        }
      val textStyle = TextStyle(color = Placeholder.text, fontSize = 20.sp)
      val cursor = SolidColor(Placeholder.text)
      val submit = {
        stopEditing()
        onSubmit()
      }
      if (secure) {
        BasicSecureTextField(
          state = field,
          modifier = modifier,
          textStyle = textStyle,
          keyboardOptions = keyboardOptions,
          onKeyboardAction = { submit() },
          cursorBrush = cursor,
          textObfuscationMode = TextObfuscationMode.Hidden,
        )
      } else {
        BasicTextField(
          state = field,
          modifier = modifier,
          textStyle = textStyle,
          keyboardOptions = keyboardOptions,
          onKeyboardAction = { submit() },
          lineLimits = TextFieldLineLimits.SingleLine,
          cursorBrush = cursor,
        )
      }
      LaunchedEffect(Unit) { fieldRequester.requestFocus() }
    } else {
      Text(
        when {
          text.isEmpty() -> placeholder
          secure -> "•".repeat(minOf(text.length, 32))
          else -> text.toString()
        },
        color = if (text.isEmpty()) Placeholder.mutedText else Placeholder.text,
        fontSize = 20.sp,
        maxLines = 1,
      )
    }
  }
}
