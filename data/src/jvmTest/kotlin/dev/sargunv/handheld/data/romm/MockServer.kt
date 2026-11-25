package dev.sargunv.handheld.data.romm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val TEST_SERVER = "https://romm.test"

/** A client for a fake server that answers with [handler] and records every request. */
class MockServer(
  baseUrl: String = TEST_SERVER,
  token: String? = "rmm_test",
  handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
  val requests = mutableListOf<HttpRequestData>()
  val client =
    RommClient(
      baseUrl,
      token,
      MockEngine { request ->
        requests += request
        handler(request)
      },
    )
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
  respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

/** The request's path below `/api/`. */
val HttpRequestData.apiPath: String
  get() = url.encodedPath.substringAfter("/api/")
