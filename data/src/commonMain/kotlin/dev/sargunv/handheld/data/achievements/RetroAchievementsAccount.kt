package dev.sargunv.handheld.data.achievements

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/**
 * A [RetroAchievementsStore] that keeps the account in a JSON file at [path]. The token is stored
 * unencrypted, so anything that can read the file can use it.
 */
class FileRetroAchievementsStore(private val path: Path) : RetroAchievementsStore {
  private val mutex = Mutex()

  override suspend fun load(): RetroAchievementsAccount? = mutex.withLock {
    if (!SystemFileSystem.exists(path)) return null
    val text = SystemFileSystem.source(path).buffered().use { it.readString() }
    json.decodeFromString(RetroAchievementsAccount.serializer(), text)
  }

  override suspend fun save(account: RetroAchievementsAccount) = mutex.withLock {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    val temp = Path("$path.tmp")
    SystemFileSystem.sink(temp).buffered().use {
      it.writeString(json.encodeToString(RetroAchievementsAccount.serializer(), account))
    }
    SystemFileSystem.atomicMove(temp, path)
  }

  override suspend fun clear() = mutex.withLock { SystemFileSystem.delete(path, mustExist = false) }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
