package dev.sargunv.handheld.data.auth

import dev.sargunv.handheld.data.JsonFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/** How this device signs in to a RomM server, and as whom. */
@Serializable
data class RommCredentials(
  /** The server's address. */
  val baseUrl: String,
  /** The client API token. */
  val token: String,
  /** The id of the RomM user the token belongs to, which stays the same across renames. */
  val userId: Long,
  /** The user's name, as of the sign-in. */
  val username: String,
  /** The server's id for this device, when the token is bound to one. */
  val deviceId: String? = null,
  /** The scopes the token holds, when the server said. */
  val scopes: List<String> = emptyList(),
) {
  override fun toString() =
    "RommCredentials(baseUrl=$baseUrl, token=<redacted>, userId=$userId, username=$username, " +
      "deviceId=$deviceId, scopes=$scopes)"
}

/**
 * Where the signed-in server's credentials live between launches. It reads them the first time
 * they're needed, and then holds what it saves.
 */
abstract class CredentialStore {
  private val mutex = Mutex()
  private val current = MutableStateFlow<RommCredentials?>(null)
  private var loaded = false

  /**
   * The stored credentials, or null while the device is signed out, as they're saved and cleared.
   */
  val credentials: Flow<RommCredentials?> = flow {
    load()
    emitAll(current)
  }

  /** The stored credentials, or null when the device isn't signed in. */
  suspend fun load(): RommCredentials? = mutex.withLock {
    if (!loaded) {
      current.value = read()
      loaded = true
    }
    current.value
  }

  suspend fun save(credentials: RommCredentials) = mutex.withLock {
    write(credentials)
    current.value = credentials
    loaded = true
  }

  suspend fun clear() = mutex.withLock {
    delete()
    current.value = null
    loaded = true
  }

  protected abstract suspend fun read(): RommCredentials?

  protected abstract suspend fun write(credentials: RommCredentials)

  protected abstract suspend fun delete()
}

/** A [CredentialStore] that forgets everything when the process ends. */
class InMemoryCredentialStore(private val initial: RommCredentials? = null) : CredentialStore() {
  override suspend fun read() = initial

  override suspend fun write(credentials: RommCredentials) {}

  override suspend fun delete() {}
}

/**
 * A [CredentialStore] that keeps the credentials in a JSON file at [path]. The token is stored
 * unencrypted, so anything that can read the file can use it. A file that doesn't decode reads as
 * signed out.
 */
class FileCredentialStore(path: Path) : CredentialStore() {
  private val file = JsonFile(path, RommCredentials.serializer(), unreadableIsMissing = true)

  override suspend fun read(): RommCredentials? = file.read()

  override suspend fun write(credentials: RommCredentials) = file.write(credentials)

  override suspend fun delete() = file.delete()
}
