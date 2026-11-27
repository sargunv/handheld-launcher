package dev.sargunv.handheld.data.romm

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.cache.LibraryCache
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class RommSessionTest {
  private val dir = createTempDirectory("romm-session-test").toFile()
  private val library = LibraryCache.open(path = null)
  private val signedIn = RommCredentials(TEST_SERVER, "rmm_test", 1, "player")
  private val store = InMemoryCredentialStore(signedIn)
  private val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())

  /** Answers the library's sync, while it isn't [held]. */
  @Volatile private var held: CompletableDeferred<Unit>? = null

  @Volatile private var refuses = false

  /** The scopes the account's role grants. */
  @Volatile private var accountScopes = """["me.read", "roms.read"]"""

  private val firstAsked = CompletableDeferred<Unit>()
  private val firstCanceled = CompletableDeferred<Unit>()

  @AfterTest
  fun cleanUp() {
    library.close()
    dir.deleteRecursively()
  }

  /** A server with one game. Its first sync waits for [held] when there is one. */
  private val server = {
    MockEngine { request ->
      requests += request
      if (refuses) return@MockEngine respondError(HttpStatusCode.Unauthorized)
      when (request.apiPath) {
        "platforms" -> {
          held?.let {
            held = null
            firstAsked.complete(Unit)
            try {
              it.await()
            } finally {
              if (!it.isCompleted) firstCanceled.complete(Unit)
            }
          }
          json("[${Fixtures.platform(1, "one", "One", 1)}]")
        }
        "roms" -> json(Fixtures.page(listOf(Fixtures.rom(1, platformId = 1)), 1, 200, 0))
        "roms/identifiers" -> json("[1]")
        "collections",
        "collections/smart" -> json("[]")
        "users/me" -> json(Fixtures.me.replace("""["me.read", "roms.read"]""", accountScopes))
        else -> respondError(HttpStatusCode.NotFound)
      }
    }
  }

  private fun session(scope: CoroutineScope) =
    RommSession(store, library, Path(dir.path, "save-sync"), scope, server)

  /** Every status [session]'s sync takes from now on, in order. */
  private fun TestScope.statuses(session: RommSession): StateFlow<List<SyncStatus>> {
    val seen = MutableStateFlow(emptyList<SyncStatus>())
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      session.sync.collect { status -> seen.update { it + status } }
    }
    return seen
  }

  private suspend fun RommSession.awaitSync(status: SyncStatus) = sync.first { it == status }

  private suspend fun RommSession.awaitUploads(access: UploadAccess) = uploadAccess.first {
    it == access
  }

  /** Signs in with [credentials], and waits for the session to have the account. */
  private suspend fun RommSession.signIn(credentials: RommCredentials): RommAccount {
    store.save(credentials)
    return state.first { (it as? RommAccount)?.credentials == credentials } as RommAccount
  }

  @Test
  fun syncsAtEachSignIn() = runTest {
    val session = session(backgroundScope)
    val seen = statuses(session)
    session.awaitSync(SyncStatus.Synced)

    store.clear()
    session.awaitSync(SyncStatus.NotStarted)
    store.save(signedIn.copy(token = "rmm_new"))
    session.awaitSync(SyncStatus.Synced)

    assertEquals(
      listOf(
        SyncStatus.NotStarted,
        SyncStatus.FirstSync,
        SyncStatus.Synced,
        SyncStatus.NotStarted,
        // The cache holds the library by now.
        SyncStatus.Syncing,
        SyncStatus.Synced,
      ),
      seen.value,
    )
    assertEquals(2, requests.count { it.apiPath == "platforms" })
  }

  @Test
  fun syncNowStartsTheSyncOver() = runTest {
    held = CompletableDeferred()
    val session = session(backgroundScope)
    val seen = statuses(session)
    firstAsked.await()

    session.syncNow()
    session.awaitSync(SyncStatus.Synced)

    firstCanceled.await()
    assertEquals(listOf(SyncStatus.NotStarted, SyncStatus.FirstSync, SyncStatus.Synced), seen.value)
    assertEquals(2, requests.count { it.apiPath == "platforms" })
  }

  @Test
  fun signingOutStopsTheSync() = runTest {
    held = CompletableDeferred()
    val session = session(backgroundScope)
    val seen = statuses(session)
    firstAsked.await()

    store.clear()
    session.awaitSync(SyncStatus.NotStarted)

    firstCanceled.await()
    assertEquals(
      listOf(SyncStatus.NotStarted, SyncStatus.FirstSync, SyncStatus.NotStarted),
      seen.value,
    )
  }

  @Test
  fun aRefusedTokenSaysSo() = runTest {
    refuses = true
    session(backgroundScope).awaitSync(SyncStatus.Unauthorized)
  }

  @Test
  fun tellsWhetherTheTokenMayUpload() = runTest {
    store.clear()
    val session = session(backgroundScope)
    session.state.first { it == RommState.SignedOut }
    assertNull(session.uploadAccess.value)

    // A sign-in that didn't ask, for an account that may upload.
    accountScopes = """["me.read", "roms.read", "roms.write"]"""
    session.signIn(signedIn.copy(deviceId = "d1", scopes = listOf("roms.read")))
    session.awaitUploads(UploadAccess.NotRequested)

    // The account may not any more, so signing in again wouldn't help.
    accountScopes = """["me.read", "roms.read"]"""
    session.syncNow()
    session.awaitUploads(UploadAccess.AccountMayNot)

    session.signIn(signedIn.copy(deviceId = "d1", scopes = listOf("roms.read", "roms.write")))
    session.awaitUploads(UploadAccess.Allowed)

    // RomM doesn't say what a pasted token may do.
    accountScopes = """["me.read", "roms.read", "roms.write"]"""
    session.signIn(signedIn.copy(token = "rmm_pasted"))
    session.awaitUploads(UploadAccess.Unknown)
    accountScopes = """["me.read", "roms.read"]"""
    session.syncNow()
    session.awaitUploads(UploadAccess.AccountMayNot)
  }

  // Each account's saves sync apart from the others', and a renewed token keeps its records.
  @Test
  fun keepsEachAccountsSavesApart() = runTest {
    val session = session(backgroundScope)
    val first = session.signIn(signedIn.copy(token = "rmm_first"))
    val renewed = session.signIn(signedIn.copy(token = "rmm_renewed"))
    val other = session.signIn(signedIn.copy(userId = 2, username = "other"))
    val elsewhere = session.signIn(signedIn.copy(baseUrl = "https://other.test"))

    assertEquals(first.saveRecords, renewed.saveRecords)
    assertEquals(first.saveHistory, renewed.saveHistory)
    for (account in listOf(other, elsewhere)) {
      assertNotEquals(first.saveRecords, account.saveRecords)
      assertNotEquals(first.saveHistory, account.saveHistory)
    }
    assertNotEquals(first.saveRecords, first.saveHistory)
  }
}
