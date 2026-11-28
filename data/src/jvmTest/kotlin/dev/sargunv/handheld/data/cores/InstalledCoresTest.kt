package dev.sargunv.handheld.data.cores

import dev.sargunv.handheld.data.httpClient
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.emulator.LibretroCores
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InstalledCoresTest {
  private val dir = Files.createTempDirectory("installed-cores-test").toFile()
  private val gambatte = LibretroCores.byLibrary("gambatte_libretro")!!
  private val mupen = LibretroCores.byLibrary("mupen64plus_next_libretro")!!
  private val azahar = LibretroCores.byLibrary("azahar_libretro")!!
  private val requests = mutableListOf<HttpRequestData>()

  @AfterTest fun deleteDir() = check(dir.deleteRecursively())

  private fun cores(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ) =
    InstalledCores(
      dir,
      "arm64-v8a",
      httpClient(
        MockEngine { request ->
          requests += request
          handler(request)
        },
      ),
    )

  private fun zipOf(name: String, content: ByteArray): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use {
      it.putNextEntry(ZipEntry(name))
      it.write(content)
      it.closeEntry()
    }
    return bytes.toByteArray()
  }

  private fun MockRequestHandleScope.zip(
    bytes: ByteArray,
    etag: String = "\"1\"",
    lastModified: String = "Wed, 25 Nov 2026 08:48:42 GMT",
    contentLength: Int = bytes.size,
  ) =
    respond(
      ByteReadChannel(bytes),
      HttpStatusCode.OK,
      headersOf(
        HttpHeaders.ContentLength to listOf("$contentLength"),
        HttpHeaders.ETag to listOf(etag),
        HttpHeaders.LastModified to listOf(lastModified),
      ),
    )

  private fun installedFile(library: String) = File(dir, "arm64-v8a/$library.so")

  private val build1 = Random(1).nextBytes(100_000)
  private val build2 = Random(2).nextBytes(120_000)

  @Test
  fun installsACoreFromTheBuildbot() = runTest {
    val cores = cores { zip(zipOf("gambatte_libretro_android.so", build1)) }
    val installed = cores.install(gambatte)

    assertEquals(
      "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/" +
        "gambatte_libretro_android.so.zip",
      requests.single().url.toString(),
    )
    assertEquals(
      InstalledCore(
        "gambatte_libretro",
        etag = "\"1\"",
        lastModified = "Wed, 25 Nov 2026 08:48:42 GMT",
      ),
      installed,
    )
    assertEquals(installedFile("gambatte_libretro"), cores.fileOf(gambatte))
    assertContentEquals(build1, installedFile("gambatte_libretro").readBytes())
    // A new instance reads the record back.
    assertEquals(installed, cores { error("no requests") }.get(gambatte))
    // Only the core and its record remain: no temporary files.
    assertEquals(
      setOf("gambatte_libretro.so", "installed.json"),
      File(dir, "arm64-v8a").list()!!.toSet(),
    )
  }

  @Test
  fun ensureInstallsAMissingCore() = runTest {
    val progress = mutableListOf<Long>()
    val zip = zipOf("gambatte_libretro_android.so", build1)
    val cores = cores { zip(zip) }

    val file = cores.ensure(gambatte) { downloaded, _ -> progress += downloaded }

    assertEquals(cores.fileOf(gambatte), file)
    assertContentEquals(build1, installedFile("gambatte_libretro").readBytes())
    assertEquals(zip.size.toLong(), progress.last())
  }

  @Test
  fun ensureUpdatesAnInstalledCoreWithANewerBuild() = runTest {
    var build = build1
    val cores = cores { request ->
      if (request.method == HttpMethod.Head) respond("", HttpStatusCode.OK)
      else zip(zipOf("gambatte_libretro_android.so", build), etag = "\"${build.size}\"")
    }
    cores.install(gambatte)
    build = build2

    cores.ensure(gambatte)

    assertContentEquals(build2, installedFile("gambatte_libretro").readBytes())
  }

  @Test
  fun ensureKeepsTheInstalledCoreWhenItCantCheckForUpdates() = runTest {
    var online = true
    val cores = cores {
      if (!online) throw java.io.IOException("offline")
      zip(zipOf("gambatte_libretro_android.so", build1))
    }
    cores.install(gambatte)
    online = false

    assertEquals(cores.fileOf(gambatte), cores.ensure(gambatte))
    assertContentEquals(build1, installedFile("gambatte_libretro").readBytes())
    assertFailsWith<java.io.IOException> { cores.ensure(mupen) }
  }

  @Test
  fun usesTheAndroidLibraryName() = runTest {
    cores { zip(zipOf("mupen64plus_next_gles3_libretro_android.so", build1)) }.install(mupen)

    assertEquals(
      "/nightly/android/latest/arm64-v8a/mupen64plus_next_gles3_libretro_android.so.zip",
      requests.single().url.encodedPath,
    )
    assertContentEquals(build1, installedFile("mupen64plus_next_libretro").readBytes())
  }

  @Test
  fun checksTheBuildbotForANewerBuild() = runTest {
    var etag = "\"1\""
    val cores = cores { request ->
      when {
        request.method == HttpMethod.Head && request.headers[HttpHeaders.IfNoneMatch] == etag ->
          respond("", HttpStatusCode.NotModified)
        request.method == HttpMethod.Head -> respond("", HttpStatusCode.OK)
        else -> zip(zipOf("gambatte_libretro_android.so", build1), etag)
      }
    }
    assertTrue(cores.hasUpdate(gambatte))
    cores.install(gambatte)

    assertFalse(cores.hasUpdate(gambatte))
    val check = requests.last()
    assertEquals("\"1\"", check.headers[HttpHeaders.IfNoneMatch])
    assertEquals("Wed, 25 Nov 2026 08:48:42 GMT", check.headers[HttpHeaders.IfModifiedSince])

    etag = "\"2\""
    assertTrue(cores.hasUpdate(gambatte))
  }

  @Test
  fun aFailedUpdateKeepsTheInstalledCore() = runTest {
    var response: MockRequestHandleScope.() -> HttpResponseData = {
      zip(zipOf("gambatte_libretro_android.so", build1))
    }
    val cores = cores { response() }
    val installed = cores.install(gambatte)

    val update = zipOf("gambatte_libretro_android.so", build2)
    val failures =
      listOf<MockRequestHandleScope.() -> HttpResponseData>(
        // The connection ends early.
        { zip(update.copyOf(update.size / 2), "\"2\"", contentLength = update.size) },
        // The server sends no length and the zip is cut short.
        {
          respond(
            ByteReadChannel(update.copyOf(update.size / 2)),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ETag, "\"2\""),
          )
        },
        // A corrupted byte in the core.
        { zip(update.copyOf().also { it[update.size / 2] = (it[update.size / 2] + 1).toByte() }) },
        // Something other than a zip.
        { zip("<html>Not here</html>".encodeToByteArray()) },
        { respond("", HttpStatusCode.NotFound) },
      )
    for (failure in failures) {
      response = failure
      assertFailsWith<CoreDownloadException> { cores.install(gambatte) }
      assertContentEquals(build1, installedFile("gambatte_libretro").readBytes())
      assertEquals(installed, cores.get(gambatte))
      assertEquals(
        setOf("gambatte_libretro.so", "installed.json"),
        File(dir, "arm64-v8a").list()!!.toSet(),
      )
    }

    response = { zip(update, "\"2\"") }
    assertEquals("\"2\"", cores.install(gambatte).etag)
    assertContentEquals(build2, installedFile("gambatte_libretro").readBytes())
  }

  @Test
  fun installsACoreFromItsGitHubRelease() = runTest {
    var tag = "2127.0.1"
    val cores = cores { request ->
      when (request.url.host) {
        "api.github.com" ->
          json(
            """
            {"tag_name": "$tag", "name": "Azahar $tag", "assets": [
              {"name": "azahar-libretro-ios-arm64-$tag.zip",
               "browser_download_url": "https://github.com/dl/ios-$tag.zip"},
              {"name": "azahar-libretro-android-arm64-v8a-$tag.zip",
               "browser_download_url": "https://github.com/dl/android-$tag.zip"}
            ]}
            """,
          )
        else -> zip(zipOf("azahar_libretro.so", build1))
      }
    }
    val installed = cores.install(azahar)

    assertEquals(
      listOf(
        "https://api.github.com/repos/azahar-emu/azahar/releases/latest",
        "https://github.com/dl/android-2127.0.1.zip",
      ),
      requests.map { it.url.toString() },
    )
    assertEquals("2127.0.1", installed.release)
    assertContentEquals(build1, installedFile("azahar_libretro").readBytes())
    assertFalse(cores.hasUpdate(azahar))

    tag = "2128.0"
    assertTrue(cores.hasUpdate(azahar))
  }

  @Test
  fun failsWhenAReleaseHasNoBuildForTheAbi() = runTest {
    val cores = cores {
      json("""{"tag_name": "2127.0.1", "assets": []}""")
    }
    assertFailsWith<CoreDownloadException> { cores.install(azahar) }
    assertNull(cores.get(azahar))
  }
}
