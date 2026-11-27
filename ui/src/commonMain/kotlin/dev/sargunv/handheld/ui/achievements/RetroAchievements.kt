package dev.sargunv.handheld.ui.achievements

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.data.achievements.RetroAchievementsException
import dev.sargunv.handheld.data.achievements.RetroAchievementsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * The device's RetroAchievements account: signing in with a username and password, which go only to
 * RetroAchievements, keeping the token it returns in [store], and signing out.
 *
 * @param client makes a client for each sign-in, which closes it after.
 */
class RetroAchievementsSignIn(
  private val store: RetroAchievementsStore,
  private val client: () -> RetroAchievementsClient,
) {
  /** The account the device is signed in to, or null. */
  suspend fun account(): RetroAchievementsAccount? = store.load()

  /**
   * Signs in and keeps the account.
   *
   * @throws RetroAchievementsException when it fails, with why as its message.
   */
  suspend fun signIn(username: String, password: String): RetroAchievementsAccount {
    val account = client().use { it.signIn(username, password) }
    store.save(account)
    return account
  }

  suspend fun signOut() = store.clear()
}

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

  /** Why the last sign-in failed, in plain words. */
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
        error = e.message
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
