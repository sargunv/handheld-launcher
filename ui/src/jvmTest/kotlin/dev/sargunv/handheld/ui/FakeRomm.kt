package dev.sargunv.handheld.ui

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A RomM server, where [route] answers each request by its path below `/api/`. */
internal fun fakeRomm(
  route: MockRequestHandleScope.(path: String, request: HttpRequestData) -> HttpResponseData,
): () -> HttpClientEngine = {
  MockEngine { request -> route(request.url.encodedPath.substringAfter("/api/"), request) }
}

internal fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
  respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** Whether the request asks for the games the player played last, rather than all of them. */
internal val HttpRequestData.lastPlayed: Boolean
  get() = url.parameters["last_played"] == "true"

internal fun page(items: List<String>) =
  """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""

internal fun platform(id: Int = 1, slug: String = "gb", name: String = "Game Boy", roms: Int = 1) =
  """{"id": $id, "slug": "$slug", "fs_slug": "$slug", "name": "$name", "display_name": "$name",
  "rom_count": $roms, "created_at": "2024-01-01T00:00:00+00:00",
  "updated_at": "2024-01-01T00:00:00+00:00"}"""

/** A game in [file], on the platform with [platform]'s id and [slug]'s slug. */
internal fun rom(
  id: Int,
  name: String = "Game $id",
  platform: Int = 1,
  slug: String = "gb",
  file: String = "$name.$slug",
  size: Int = 1024,
  added: String = "2024-01-01",
  lastPlayed: String? = null,
): String {
  val stem = file.substringBeforeLast('.')
  val user = lastPlayed?.let { """, "rom_user": {"last_played": "${it}T00:00:00+00:00"}""" }
  return """{"id": $id, "platform_id": $platform, "platform_slug": "$slug",
  "platform_fs_slug": "$slug", "platform_display_name": "$slug", "name": "$name",
  "fs_name": "$file", "fs_name_no_tags": "$stem", "fs_name_no_ext": "$stem",
  "fs_extension": "${file.substringAfterLast('.')}", "fs_size_bytes": $size,
  "path_cover_small": "", "url_cover": "", "created_at": "${added}T00:00:00+00:00",
  "updated_at": "${added}T00:00:00+00:00"${user.orEmpty()}}"""
}

internal fun collection(id: Int, name: String, roms: List<Int>) =
  """{"id": $id, "name": "$name", "rom_ids": $roms, "rom_count": ${roms.size}, "user_id": 1,
  "owner_username": "me", "created_at": "2024-01-01T00:00:00+00:00",
  "updated_at": "2024-01-01T00:00:00+00:00"}"""
