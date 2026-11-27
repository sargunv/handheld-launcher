package dev.sargunv.handheld.data.romm

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.LibrarySync
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/** Whether the device is signed in to a RomM server: [Unknown], [SignedOut] or a [RommAccount]. */
sealed interface RommState {
  /** The stored credentials haven't been read yet. */
  data object Unknown : RommState

  data object SignedOut : RommState
}

/**
 * The device signed in to a RomM server with [credentials]. [client] is the one client for them,
 * which everything signed in shares and nothing closes.
 */
class RommAccount
internal constructor(val credentials: RommCredentials, val client: RommClient, saveRoot: Path) :
  RommState {
  private val root =
    Path(saveRoot, credentials.baseUrl.encodeURLParameter(), credentials.userId.toString())

  /** Where save sync keeps its record of each save it synced for this account. */
  val saveRecords: Path = Path(root, "records")

  /** Where save sync keeps the copies of this account's saves that it replaced. */
  val saveHistory: Path = Path(root, "history")
}

/** How the library's latest sync with the server went. */
enum class SyncStatus {
  NotStarted,
  /** Syncing into an empty cache, which fills in a piece at a time. */
  FirstSync,
  Syncing,
  Synced,
  /** The server couldn't be reached, or failed. */
  Failed,
  /** The server refused the device's token, so syncing waits for a new sign-in. */
  Unauthorized;

  /** Whether the sync has finished, one way or another. */
  val isDone: Boolean
    get() = this == Synced || this == Failed || this == Unauthorized
}

/**
 * The device's sign-in to a RomM server, for as long as the app runs, as [credentials] holds it.
 * While signed in, it syncs [library] with the server, at the sign-in and on [syncNow]. Signing out
 * stops the sync.
 *
 * @param saveRoot where save sync keeps each account's records and history, apart from the others'.
 * @param engine makes the HTTP engine for each sign-in's client.
 */
class RommSession(
  credentials: CredentialStore,
  private val library: LibraryCache,
  saveRoot: Path,
  scope: CoroutineScope,
  engine: () -> HttpClientEngine,
) {
  /** Signs in to a server, and out of it. */
  val signIn = RommSignIn(credentials, engine)

  /**
   * Whether the device is signed in, and with what. Each sign-in opens a client that's never
   * closed, so a download begun with it finishes after a sign-out.
   */
  val state: StateFlow<RommState> =
    credentials.credentials
      .map {
        if (it == null) RommState.SignedOut
        else RommAccount(it, RommClient(it.baseUrl, it.token, engine()), saveRoot)
      }
      .stateIn(scope, SharingStarted.Eagerly, RommState.Unknown)

  /** The signed-in account, or null while signed out or before the credentials have been read. */
  val account: RommAccount?
    get() = state.value as? RommAccount

  private val syncStatus = MutableStateFlow(SyncStatus.NotStarted)

  /** How the library's latest sync went, or [SyncStatus.NotStarted] while signed out. */
  val sync: StateFlow<SyncStatus> = syncStatus.asStateFlow()

  private val syncRequests =
    MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val uploads = MutableStateFlow<UploadAccess?>(null)

  /**
   * Whether the signed-in token may upload games to the server, or null while signed out or until
   * the server says. Each sync asks again, since the account's permissions can change.
   */
  val uploadAccess: StateFlow<UploadAccess?> = uploads.asStateFlow()

  init {
    scope.launch {
      state.collectLatest { account ->
        uploads.value = null
        if (account is RommAccount) syncLibrary(account)
        else syncStatus.value = SyncStatus.NotStarted
      }
    }
  }

  /** Syncs the library with the server again, starting over if a sync is under way. */
  fun syncNow() {
    syncRequests.tryEmit(Unit)
  }

  private suspend fun syncLibrary(account: RommAccount) {
    val librarySync = LibrarySync(account.client, library, account.credentials.userId)
    // A new request starts the sync over.
    syncRequests
      .onStart { emit(Unit) }
      .collectLatest {
        coroutineScope {
          launch { uploads.value = uploadAccess(account) }
          syncStatus.value = if (librarySync.isFirst()) SyncStatus.FirstSync else SyncStatus.Syncing
          syncStatus.value =
            try {
              librarySync.sync()
              SyncStatus.Synced
            } catch (_: RommException.Unauthorized) {
              SyncStatus.Unauthorized
            } catch (e: RommException) {
              log.w(e) { "Library sync failed" }
              SyncStatus.Failed
            }
        }
      }
  }
}

/**
 * What [account]'s token may upload. A device sign-in stores the scopes the server granted. When
 * those lack uploading, or a pasted token's scopes are unknown, the account's own scopes, which the
 * server reports, tell whether signing in again would help.
 */
private suspend fun uploadAccess(account: RommAccount): UploadAccess {
  val scopes = account.credentials.scopes
  if (UPLOAD_SCOPE in scopes) return UploadAccess.Allowed
  val accountScopes =
    try {
      account.client.me().oauthScopes
    } catch (e: RommException) {
      log.w(e) { "Couldn't read the account's scopes" }
      emptyList()
    }
  return when {
    accountScopes.isNotEmpty() && UPLOAD_SCOPE !in accountScopes -> UploadAccess.AccountMayNot
    scopes.isEmpty() -> UploadAccess.Unknown
    else -> UploadAccess.NotRequested
  }
}

private val log = Logger.withTag("RommSession")
