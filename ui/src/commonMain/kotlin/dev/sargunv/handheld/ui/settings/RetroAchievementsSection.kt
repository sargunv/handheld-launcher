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
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn.Attempt
import dev.sargunv.handheld.data.settings.AchievementSettings
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.widgets.TextEntry

internal fun LazyListScope.retroAchievements(
  state: AppState,
  settings: AchievementSettings,
  signedIn: RetroAchievementsAccount?,
  attempt: Attempt?,
) {
  val account = state.retroAchievements ?: return
  val view = state.settingsView
  sectionHeading("RetroAchievements")
  if (signedIn != null) {
    item(key = "account") {
      SettingRow(
        view,
        "settings:ra:account",
        "Account",
        signedIn.username,
        "The RetroAchievements user this device signed in as",
      )
    }
    item(key = "hardcore") {
      SettingRow(
        view,
        "settings:ra:hardcore",
        "Hardcore",
        if (settings.hardcore) "On" else "Off",
        "In hardcore, games with achievements can't load states or fast forward",
        onConfirm = {
          state.data.settings.update {
            it.copy(achievements = it.achievements.copy(hardcore = !it.achievements.hardcore))
          }
        },
      )
    }
    item(key = "signOut") {
      SettingRow(
        view,
        "settings:ra:signOut",
        "Sign out",
        null,
        "Signs this device out of RetroAchievements",
        onConfirm = { state.platform.retroAchievements?.signOut() },
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
      help = null,
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
      "Earns achievements in games that play in Built-in",
      enabled = attempt != Attempt.Running,
      onConfirm = account::submit,
    )
  }
  val status = account.status(attempt)
  if (status != null) {
    item(key = "status") {
      Text(
        status,
        color = Palette.text,
        modifier =
          Modifier.testTag("settings:ra:status").semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}
