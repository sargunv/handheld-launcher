package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.sargunv.handheld.ui.signin.Help
import dev.sargunv.handheld.ui.signin.TextEntry
import kotlinx.coroutines.launch

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
        Help("Account", "The RetroAchievements user this device signed in as."),
      )
    }
    item(key = "hardcore") {
      SettingRow(
        view,
        "settings:ra:hardcore",
        "Hardcore",
        if (settings.hardcore) "On" else "Off",
        Help("Hardcore", "In hardcore, games with achievements can't load states or fast forward."),
        onConfirm = {
          state.settings =
            state.settings.copy(achievements = settings.copy(hardcore = !settings.hardcore))
        },
      )
    }
    item(key = "signOut") {
      val scope = rememberCoroutineScope()
      SettingRow(
        view,
        "settings:ra:signOut",
        "Sign out",
        null,
        Help("Sign out", "Signs this device out of RetroAchievements."),
        onConfirm = { scope.launch { account.signOut() } },
      )
    }
    return
  }
  item(key = "username") {
    val help = Help("Username", "Your RetroAchievements username.")
    TextEntry(
      key = "settings:ra:username",
      field = account.usernameField,
      placeholder = "Username",
      label = help.title,
      keyboardOptions =
        KeyboardOptions(
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Next,
        ),
      onSubmit = {},
      modifier = Modifier.fillMaxWidth(),
      onFocused = { view.help = help },
    )
  }
  item(key = "password") {
    val scope = rememberCoroutineScope()
    val help = Help("Password", "Goes only to RetroAchievements. The device keeps a token instead.")
    TextEntry(
      key = "settings:ra:password",
      field = account.passwordField,
      placeholder = "Password",
      label = help.title,
      keyboardOptions =
        KeyboardOptions(
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done,
        ),
      onSubmit = { scope.launch { account.submit() } },
      modifier = Modifier.fillMaxWidth(),
      secure = true,
      onFocused = { view.help = help },
    )
  }
  item(key = "signIn") {
    val scope = rememberCoroutineScope()
    SettingRow(
      view,
      "settings:ra:signIn",
      "Sign in",
      null,
      Help("Sign in", "Earns achievements in games that play in Built-in."),
      enabled = !account.busy,
      onConfirm = { scope.launch { account.submit() } },
    )
  }
  val status = if (account.busy) "Signing in…" else account.error
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
