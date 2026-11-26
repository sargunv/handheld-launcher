@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.auth

import dev.sargunv.handheld.data.cores.JsonFile
import dev.sargunv.handheld.data.romm.RommInstantSerializer
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** How this device signs in to a RomM server. */
@Serializable
data class RommCredentials(
  /** The server's address. */
  val baseUrl: String,
  /** The client API token. */
  val token: String,
  /** The server's id for this device, when the token is bound to one. */
  val deviceId: String? = null,
  /** The scopes the token holds, when the server said. */
  val scopes: List<String> = emptyList(),
  /** When the token expires, or null when it doesn't or the server didn't say. */
  val expiresAt: Instant? = null,
) {
  override fun toString() =
    "RommCredentials(baseUrl=$baseUrl, token=<redacted>, deviceId=$deviceId, scopes=$scopes, " +
      "expiresAt=$expiresAt)"
}

/** Where the signed-in server's credentials live between launches. */
interface CredentialStore {
  /** The stored credentials, or null when the device isn't signed in. */
  suspend fun load(): RommCredentials?

  suspend fun save(credentials: RommCredentials)

  suspend fun clear()
}

/** A [CredentialStore] that forgets everything when the process ends. */
class InMemoryCredentialStore(private var credentials: RommCredentials? = null) : CredentialStore {
  private val mutex = Mutex()

  override suspend fun load() = mutex.withLock { credentials }

  override suspend fun save(credentials: RommCredentials) = mutex.withLock {
    this.credentials = credentials
  }

  override suspend fun clear() = mutex.withLock { credentials = null }
}

/**
 * A [CredentialStore] that keeps the credentials in a JSON file at [path]. The token is stored
 * unencrypted, so anything that can read the file can use it.
 */
class FileCredentialStore(private val path: Path) : CredentialStore {
  private val file = JsonFile(path, RommCredentials.serializer())
  private val mutex = Mutex()

  override suspend fun load(): RommCredentials? = mutex.withLock { file.read() }

  override suspend fun save(credentials: RommCredentials) = mutex.withLock {
    file.write(credentials)
  }

  override suspend fun clear() = mutex.withLock { SystemFileSystem.delete(path, mustExist = false) }
}
