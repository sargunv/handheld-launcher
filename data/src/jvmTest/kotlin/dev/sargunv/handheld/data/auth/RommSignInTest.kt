package dev.sargunv.handheld.data.auth

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.SecretStore
import dev.sargunv.handheld.data.Vault
import dev.sargunv.handheld.data.httpClient
import dev.sargunv.handheld.data.romm.DefaultDeviceScopes
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RommSignInTest {
  private val store = SecretStore(Vault.InMemory<RommCredentials>())
  private val requests = mutableListOf<HttpRequestData>()
  private var polls = 0

  private val signIn =
    RommSignIn(
      store,
      httpClient(
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
              json("""{"access_token": "rmm_issued", "device_id": "d1", "scopes": ["roms.read"]}""")
            path == "/api/devices/d1" && request.method == HttpMethod.Put -> json("{}")
            path == "/api/users/me" && token in setOf("Bearer rmm_good", "Bearer rmm_issued") ->
              json(Fixtures.me)
            path == "/api/users/me" ->
              json("""{"detail":"Invalid token"}""", HttpStatusCode.Unauthorized)
            else -> respondError(HttpStatusCode.NotFound)
          }
        },
      ),
    )

  private val device = DeviceDescription("install-1", "Thor", "android", "0.1.0")

  @Test
  fun pairsAndStoresTheIssuedToken() = runTest {
    val codes = mutableListOf<DeviceCode>()
    val credentials = signIn.withDeviceCode(TEST_SERVER, device) { codes += it }

    assertEquals("ABCD2345", codes.single().userCode)
    assertEquals("$TEST_SERVER/pair/device?user_code=ABCD2345", codes.single().verificationUrl)
    assertEquals(
      RommCredentials(TEST_SERVER, "rmm_issued", 1, "player", "d1", listOf("roms.read")),
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

  /** The scopes the device asked for when it started pairing. */
  private fun requestedScopes(): List<String> {
    val init = requests.single { it.url.encodedPath == "/api/auth/device/init" }
    val body = Json.parseToJsonElement((init.body as TextContent).text).jsonObject
    return body.getValue("requested_scopes").jsonArray.map { it.jsonPrimitive.content }
  }

  @Test
  fun asksForTheUsualScopesUnlessUploadsAreAllowed() = runTest {
    signIn.withDeviceCode(TEST_SERVER, device) {}
    assertEquals(DefaultDeviceScopes, requestedScopes())
    assertFalse("roms.write" in requestedScopes())

    requests.clear()
    polls = 0
    signIn.withDeviceCode(TEST_SERVER, device, allowUploads = true) {}
    assertEquals(DefaultDeviceScopes + listOf("roms.write", "tasks.run"), requestedScopes())
  }

  @Test
  fun storesAWorkingToken() = runTest {
    val credentials = signIn.withToken(TEST_SERVER, "  rmm_good\n")
    assertEquals(RommCredentials(TEST_SERVER, "rmm_good", 1, "player"), credentials)
    assertEquals(credentials, store.load())
  }

  @Test
  fun rejectsATokenTheServerDoesNotAccept() = runTest {
    assertFailsWith<RommException.Unauthorized> { signIn.withToken(TEST_SERVER, "rmm_bad") }
    assertNull(store.load())
  }

  @Test
  fun refusesAnAddressTheEngineCannotSendTo() = runTest {
    val signIn = RommSignIn(store, httpClient(MockEngine { error("No request should go out") }))
    for (address in listOf("https://romm%20x.com", "https://romm..example.com")) {
      assertFailsWith<RommException.Unreachable> { signIn.withToken(address, "rmm_good") }
    }
  }

  /** A server at [http] whose heartbeat answers [heartbeat], and that takes `rmm_good` anywhere. */
  private fun plainServer(heartbeat: (MockRequestHandleScope) -> HttpResponseData) =
    RommSignIn(
      store,
      httpClient(
        MockEngine { request ->
          requests += request
          when (request.url.encodedPath) {
            "/romm/api/heartbeat" -> heartbeat(this)
            "/romm/api/users/me" ->
              if (request.headers[HttpHeaders.Authorization] == "Bearer rmm_good") json(Fixtures.me)
              else json("""{"detail":"Invalid token"}""", HttpStatusCode.Unauthorized)
            else -> respondError(HttpStatusCode.NotFound)
          }
        },
      ),
    )

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
