package dev.sargunv.handheld.ui.achievements

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.achievements.RetroAchievementsException
import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.signin.SIGN_IN_NOT_SAVED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.Serializable

/** How games in the built-in session take part in RetroAchievements. */
@Serializable
data class AchievementSettings(
  /**
   * Whether unlocks count as hardcore. In hardcore, games with achievements can't load states or
   * fast forward, as RetroAchievements requires.
   */
  val hardcore: Boolean = false,
)

/**
 * Settings' view of the RetroAchievements account. A sign-in or sign-out runs in [scope], so it
 * finishes even when Settings goes away.
 */
@Stable
class RetroAchievementsState(
  private val signIn: RetroAchievementsSignIn,
  private val scope: CoroutineScope,
) {
  /** The signed-in username, or null when signed out or not yet read. */
  var username by mutableStateOf<String?>(null)
    private set

  /** Whether a sign-in is under way. */
  var busy by mutableStateOf(false)
    private set

  /** How the last sign-in failed. */
  var error by mutableStateOf<String?>(null)
    private set

  internal val usernameField = TextFieldState()
  internal val passwordField = TextFieldState()

  /** Reads the stored account. */
  suspend fun load() {
    username = signIn.account()?.username
  }

  /** Signs in with what's in the fields, and forgets the password whatever happens. */
  fun submit() {
    if (busy) return
    val user = usernameField.text.toString().trim()
    val password = passwordField.text.toString()
    if (user.isEmpty() || password.isEmpty()) {
      error = "Username and password required"
      return
    }
    busy = true
    error = null
    scope.launch {
      try {
        username = signIn.signIn(user, password).username
        usernameField.clearText()
      } catch (e: RetroAchievementsException) {
        log.w(e) { "RetroAchievements sign-in failed" }
        error = labelled("Sign-in failed", e.message)
      } catch (e: IOException) {
        log.w(e) { "Saving the RetroAchievements sign-in failed" }
        error = SIGN_IN_NOT_SAVED
      } finally {
        passwordField.clearText()
        busy = false
      }
    }
  }

  fun signOut() {
    scope.launch {
      signIn.signOut()
      username = null
      error = null
    }
  }
}

private val log = Logger.withTag("RetroAchievements")
