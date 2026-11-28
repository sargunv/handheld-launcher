package dev.sargunv.handheld.ui.achievements

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.achievements.RetroAchievementsException
import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn.Attempt
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.signin.SIGN_IN_NOT_SAVED

/** Settings' view of the RetroAchievements account, whose sign-ins [signIn] runs. */
@Stable
class RetroAchievementsState(val signIn: RetroAchievementsSignIn) {
  /** Whether the last submit lacked a username or a password. */
  private var incomplete by mutableStateOf(false)

  internal val usernameField = TextFieldState()
  internal val passwordField = TextFieldState()

  /** Signs in with what's in the fields, and forgets the password whatever happens. */
  fun submit() {
    val user = usernameField.text.toString().trim()
    val password = passwordField.text.toString()
    incomplete = user.isEmpty() || password.isEmpty()
    if (!incomplete) signIn.signIn(user, password)
    passwordField.clearText()
  }

  /** What to say about the sign-in [attempt], or null for nothing. */
  fun status(attempt: Attempt?): String? =
    when {
      attempt == Attempt.Running -> "Signing in"
      incomplete -> "Username and password required"
      attempt !is Attempt.Failed -> null
      attempt.cause is RetroAchievementsException ->
        labelled("Sign-in failed", attempt.cause.message)
      else -> SIGN_IN_NOT_SAVED
    }
}
