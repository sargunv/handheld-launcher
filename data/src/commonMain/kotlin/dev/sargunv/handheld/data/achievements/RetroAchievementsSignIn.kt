package dev.sargunv.handheld.data.achievements

import kotlinx.io.IOException

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
   * @throws RetroAchievementsException when it fails, with why as its message when that's known.
   * @throws IOException when the account can't be saved.
   */
  suspend fun signIn(username: String, password: String): RetroAchievementsAccount {
    val account = client().use { it.signIn(username, password) }
    store.save(account)
    return account
  }

  suspend fun signOut() = store.clear()
}
