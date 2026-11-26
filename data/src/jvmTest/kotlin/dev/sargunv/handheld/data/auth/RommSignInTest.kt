package dev.sargunv.handheld.data.auth

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.romm.DeviceCode
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.TEST_SERVER
import dev.sargunv.handheld.data.romm.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

class RommSignInTest {
  private val store = InMemoryCredentialStore()
  private val requests = mutableListOf<HttpRequestData>()
  private var polls = 0

  private val signIn =
    RommSignIn(store) {
      MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        val token = request.headers[HttpHeaders.Authorization]
        when {
          path == "/api/auth/device/init" ->
            json(
              """
              {"device_code": "secret", "user_code": "ABCD2345", "verification_path": "/pair/device",
               "verification_path_complete": "/pair/device?user_code=ABCD2345",
               "expires_in": 600, "interval": 5}
              """,
              HttpStatusCode.Created,
            )
          path == "/api/auth/device/token" && polls++ == 0 ->
            json("""{"detail":"authorization_pending"}""", HttpStatusCode.BadRequest)
          path == "/api/auth/device/token" ->
            json(
              """{"access_token": "rmm_issued", "device_id": "d1", "scopes": ["roms.read"],
                  "expires_at": "2026-01-01T00:00:00+00:00"}""",
            )
          path == "/api/devices/d1" && request.method == HttpMethod.Put -> json("{}")
          path == "/api/users/me" && token == "Bearer rmm_good" -> json(Fixtures.me)
          path == "/api/users/me" ->
            json("""{"detail":"Invalid token"}""", HttpStatusCode.Unauthorized)
          else -> respondError(HttpStatusCode.NotFound)
        }
      }
    }

  private val device = DeviceDescription("install-1", "Thor", "android", "0.1.0")

  @Test
  fun pairsAndStoresTheIssuedToken() = runTest {
    val codes = mutableListOf<DeviceCode>()
    val credentials = signIn.withDeviceCode(TEST_SERVER, device) { codes += it }

    assertEquals("ABCD2345", codes.single().userCode)
    assertEquals("$TEST_SERVER/pair/device?user_code=ABCD2345", codes.single().verificationUrl)
    assertEquals(
      RommCredentials(
        TEST_SERVER,
        "rmm_issued",
        "d1",
        listOf("roms.read"),
        Instant.parse("2026-01-01T00:00:00Z"),
      ),
      credentials,
    )
    assertEquals(credentials, store.load())
    assertEquals(2, polls)
    // The device says it takes games sent from the server's web page.
    val update = requests.single { it.method == HttpMethod.Put }
    assertEquals("/api/devices/d1", update.url.encodedPath)
    assertEquals("Bearer rmm_issued", update.headers[HttpHeaders.Authorization])
    assertEquals("""{"capabilities":{"remote_install":true}}""", (update.body as TextContent).text)
  }

  @Test
  fun storesAWorkingToken() = runTest {
    val credentials = signIn.withToken(TEST_SERVER, "  rmm_good\n")
    assertEquals(RommCredentials(TEST_SERVER, "rmm_good"), credentials)
    assertEquals(credentials, store.load())
  }

  @Test
  fun rejectsATokenTheServerDoesNotAccept() = runTest {
    assertFailsWith<RommException.Unauthorized> { signIn.withToken(TEST_SERVER, "rmm_bad") }
    assertNull(store.load())
  }

  @Test
  fun makesAClientForTheSignedInServer() = runTest {
    assertNull(signIn.client())
    signIn.withToken(TEST_SERVER, "rmm_good")
    signIn.client()!!.use { it.me() }
    assertEquals("Bearer rmm_good", requests.last().headers[HttpHeaders.Authorization])

    signIn.signOut()
    assertNull(store.load())
  }

  @Test
  fun keepsCredentialsInAFile() = runTest {
    val dir = Path(SystemTemporaryDirectory, "credentials-test-${Random.nextLong().toULong()}")
    val path = Path(dir, "credentials.json")
    val fileStore = FileCredentialStore(path)
    try {
      assertNull(fileStore.load())
      val credentials = RommCredentials(TEST_SERVER, "rmm_secret", "d1", listOf("roms.read"), null)
      fileStore.save(credentials)
      assertEquals(credentials, FileCredentialStore(path).load())
      assertFalse("rmm_secret" in credentials.toString())

      fileStore.clear()
      assertNull(fileStore.load())
      assertTrue(SystemFileSystem.exists(dir))
    } finally {
      SystemFileSystem.delete(path, mustExist = false)
      SystemFileSystem.delete(dir, mustExist = false)
    }
  }

  /** A server at [http] whose heartbeat answers [heartbeat], and that takes `rmm_good` anywhere. */
  private fun plainServer(heartbeat: (MockRequestHandleScope) -> HttpResponseData) =
    RommSignIn(store) {
      MockEngine { request ->
        requests += request
        when (request.url.encodedPath) {
          "/romm/api/heartbeat" -> heartbeat(this)
          "/romm/api/users/me" ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer rmm_good") json(Fixtures.me)
            else json("""{"detail":"Invalid token"}""", HttpStatusCode.Unauthorized)
          else -> respondError(HttpStatusCode.NotFound)
        }
      }
    }

  @Test
  fun switchesToHttpsWhenThePlainAddressRedirectsThere() = runTest {
    val signIn = plainServer {
      it.respond(
        "",
        HttpStatusCode.MovedPermanently,
        headersOf(HttpHeaders.Location, "https://romm.test/romm/api/heartbeat"),
      )
    }
    val credentials = signIn.withToken("http://romm.test/romm", "rmm_good")
    assertEquals("https://romm.test/romm", credentials.baseUrl)
    // The token only ever went to the encrypted address.
    val plain = requests.filter { it.url.protocol == URLProtocol.HTTP }
    assertEquals(listOf("/romm/api/heartbeat"), plain.map { it.url.encodedPath })
    assertTrue(plain.all { it.headers[HttpHeaders.Authorization] == null })
  }

  @Test
  fun keepsAPlainAddressThatDoesNotRedirect() = runTest {
    val signIn = plainServer { it.json("""{"SYSTEM":{"VERSION":"4.0.0"}}""") }
    val credentials = signIn.withToken("http://romm.test/romm", "rmm_good")
    assertEquals("http://romm.test/romm", credentials.baseUrl)
  }
}
