package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.signin.TextEntry

internal fun LazyListScope.retroAchievements(state: AppState) {
  val account = state.retroAchievements ?: return
  val view = state.settingsView
  val settings = state.settings.achievements
  sectionHeading("RetroAchievements")
  val username = account.username
  if (username != null) {
    item(key = "account") {
      SettingRow(
        view,
        "settings:ra:account",
        "Account",
        username,
        "The RetroAchievements user this device signed in as.",
      )
    }
    item(key = "hardcore") {
      SettingRow(
        view,
        "settings:ra:hardcore",
        "Hardcore",
        if (settings.hardcore) "On" else "Off",
        "In hardcore, games with achievements can't load states or fast forward.",
        onConfirm = {
          state.settings =
            state.settings.copy(achievements = settings.copy(hardcore = !settings.hardcore))
        },
      )
    }
    item(key = "signOut") {
      SettingRow(
        view,
        "settings:ra:signOut",
        "Sign out",
        null,
        "Signs this device out of RetroAchievements.",
        onConfirm = account::signOut,
      )
    }
    return
  }
  item(key = "username") {
    TextEntry(
      view.help,
      key = "settings:ra:username",
      field = account.usernameField,
      placeholder = "Username",
      label = "Username",
      help = "Your RetroAchievements username.",
      keyboardOptions =
        KeyboardOptions(
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Next,
        ),
      onSubmit = {},
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item(key = "password") {
    TextEntry(
      view.help,
      key = "settings:ra:password",
      field = account.passwordField,
      placeholder = "Password",
      label = "Password",
      help = "Sent only to RetroAchievements",
      keyboardOptions =
        KeyboardOptions(
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done,
        ),
      onSubmit = account::submit,
      modifier = Modifier.fillMaxWidth(),
      secure = true,
    )
  }
  item(key = "signIn") {
    SettingRow(
      view,
      "settings:ra:signIn",
      "Sign in",
      null,
      "Earns achievements in games that play in Built-in.",
      enabled = !account.busy,
      onConfirm = account::submit,
    )
  }
  val status = if (account.busy) "Signing in" else account.error
  if (status != null) {
    item(key = "status") {
      Text(
        status,
        color = Placeholder.text,
        modifier =
          Modifier.testTag("settings:ra:status").semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}
