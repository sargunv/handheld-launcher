package dev.sargunv.handheld.data.achievements

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * The RetroAchievements account the device is signed in to: the username, and the token the server
 * issued for it, which stands in for the password. The password itself is never kept.
 */
@Serializable
data class RetroAchievementsAccount(val username: String, val token: String) {
  override fun toString() = "RetroAchievementsAccount(username=$username, token=<redacted>)"
}

/** Where the RetroAchievements account lives between launches. */
interface RetroAchievementsStore {
  /** The account the device is signed in to, or null when it isn't. */
  suspend fun load(): RetroAchievementsAccount?

  suspend fun save(account: RetroAchievementsAccount)

  suspend fun clear()
}

/** A [RetroAchievementsStore] that forgets everything when the process ends. */
class InMemoryRetroAchievementsStore(private var account: RetroAchievementsAccount? = null) :
  RetroAchievementsStore {
  private val mutex = Mutex()

  override suspend fun load() = mutex.withLock { account }

  override suspend fun save(account: RetroAchievementsAccount) = mutex.withLock {
    this.account = account
  }

  override suspend fun clear() = mutex.withLock { account = null }
}
