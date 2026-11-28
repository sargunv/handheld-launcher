package dev.sargunv.handheld.data.achievements

import dev.sargunv.handheld.data.SecretStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The RetroAchievements account the device is signed in to: the username, and the token the server
 * issued for it, which stands in for the password. The password itself is never kept.
 */
@Serializable
data class RetroAchievementsAccount(val username: String, val token: String) {
  override fun toString() = "RetroAchievementsAccount(username=$username, token=<redacted>)"
}

/**
 * The device's RetroAchievements account: signing in with a username and password, which go only to
 * RetroAchievements, keeping the token it returns in [store], and signing out. Both run in [scope],
 * so they finish when the screen that asked goes away.
 */
class RetroAchievementsSignIn(
  private val store: SecretStore<RetroAchievementsAccount>,
  private val client: RetroAchievementsClient,
  private val scope: CoroutineScope,
) {
  /** A sign-in under way, or how the last one failed. */
  sealed interface Attempt {
    data object Running : Attempt

    /**
     * The sign-in failed: a [RetroAchievementsException], with why as its message when that's
     * known, or an [IOException] when the account couldn't be saved.
     */
    data class Failed(val cause: Exception) : Attempt
  }

  /** The account the device is signed in to, or null while it isn't or isn't read yet. */
  val account: StateFlow<RetroAchievementsAccount?> =
    store.value.stateIn(scope, SharingStarted.Eagerly, null)

  private val attempts = MutableStateFlow<Attempt?>(null)

  val attempt: StateFlow<Attempt?> = attempts.asStateFlow()

  /** Signs in and keeps the account, unless a sign-in is under way. */
  fun signIn(username: String, password: String) {
    val last = attempts.value
    if (last == Attempt.Running || !attempts.compareAndSet(last, Attempt.Running)) return
    scope.launch {
      attempts.value =
        try {
          store.save(client.signIn(username, password))
          null
        } catch (e: RetroAchievementsException) {
          Attempt.Failed(e)
        } catch (e: IOException) {
          Attempt.Failed(e)
        }
    }
  }

  fun signOut() {
    attempts.value = null
    scope.launch { store.clear() }
  }
}
