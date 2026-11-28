package dev.sargunv.handheld.data.firmware

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.saves.BiosKnowledge
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FirmwareInstallerTest {
  private val root = Files.createTempDirectory("firmware-test").toFile()
  private val staging = File(root, "staging")
  private val folder = File(root, "bios")

  @AfterTest fun cleanUp() = check(root.deleteRecursively())

  private val bios = "PlayStation BIOS".encodeToByteArray()
  private val dcBoot = "Dreamcast boot ROM".encodeToByteArray()

  private class ServedFile(
    val id: Long,
    val platformId: Long,
    val name: String,
    val bytes: ByteArray,
    val md5: String?,
  )

  /** While set, a firmware download's connection breaks after this many bytes. */
  private var breakAfter: Int? = null

  private fun md5(bytes: ByteArray) =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

  /**
   * A server with PlayStation (id 51) and Dreamcast (id 54) platforms, serving [files] and honoring
   * ranges.
   */
  private fun server(vararg files: ServedFile) = MockServer { request ->
    val path = request.apiPath
    when {
      path == "platforms" ->
        json(
          "[${Fixtures.platform(51, "psx", "PlayStation", 1)}," +
            "${Fixtures.platform(54, "dc", "Dreamcast", 1)}]",
        )
      path == "firmware" -> {
        val platformId = request.url.parameters["platform_id"]?.toLong()
        json(
          files
            .filter { it.platformId == platformId }
            .joinToString(",", "[", "]") {
              """
              {"id": ${it.id}, "platform_id": ${it.platformId}, "file_name": "${it.name}",
               "file_size_bytes": ${it.bytes.size}, "is_verified": true,
               "md5_hash": ${it.md5?.let { md5 -> "\"$md5\"" }}, "sha1_hash": null,
               "crc_hash": null, "missing_from_fs": false,
               "updated_at": "2025-01-02T00:00:00+00:00"}
              """
            },
        )
      }
      path.startsWith("firmware/") -> {
        val file = files.first { path == "firmware/${it.id}/content/${it.name}" }
        val start =
          request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        val rest = file.bytes.copyOfRange(start ?: 0, file.bytes.size)
        val body = ByteReadChannel(rest.copyOf(minOf(breakAfter ?: rest.size, rest.size)))
        if (start == null) {
          respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${rest.size}"))
        } else {
          respond(
            body,
            HttpStatusCode.PartialContent,
            headersOf(
              HttpHeaders.ContentRange,
              "bytes $start-${file.bytes.size - 1}/${file.bytes.size}",
            ),
          )
        }
      }
      else -> error("Unexpected request to $path")
    }
  }

  private val duckStation = BiosKnowledge.locationFor(EmulatorId("duckstation"))!!

  @Test
  fun placesMissingFirmware() = runTest {
    val server = server(ServedFile(1, 51, "scph5501.bin", bios, md5(bios)))
    val result =
      FirmwareInstaller(server.client, staging)
        .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))

    assertEquals(
      FirmwareInstall.Done(folder.path, listOf("scph5501.bin"), emptyList(), emptyMap()),
      result,
    )
    assertContentEquals(bios, File(folder, "scph5501.bin").readBytes())
    assertEquals(
      listOf("scph5501.bin"),
      folder.list()!!.toList(),
      "No partial files are left behind",
    )
    assertTrue(staging.list().orEmpty().isEmpty(), "The staged download is cleaned up")
  }

  @Test
  fun leavesAnExistingFileAlone() = runTest {
    folder.mkdirs()
    val mine = "The user's own BIOS".encodeToByteArray()
    File(folder, "scph5501.bin").writeBytes(mine)
    val server = server(ServedFile(1, 51, "scph5501.bin", bios, md5(bios)))
    val result =
      FirmwareInstaller(server.client, staging)
        .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))

    assertEquals(
      FirmwareInstall.Done(folder.path, emptyList(), listOf("scph5501.bin"), emptyMap()),
      result,
    )
    assertContentEquals(mine, File(folder, "scph5501.bin").readBytes())
    assertFalse(
      server.requests.any { it.apiPath.endsWith("/content/scph5501.bin") },
      "It isn't downloaded",
    )
  }

  @Test
  fun refusesADownloadThatFailsItsHash() = runTest {
    val server = server(ServedFile(1, 51, "scph5501.bin", bios, md5(dcBoot)))
    val result =
      FirmwareInstaller(server.client, staging)
        .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))

    val done = assertIs<FirmwareInstall.Done>(result)
    assertEquals(setOf("scph5501.bin"), done.failed.keys)
    assertFalse(File(folder, "scph5501.bin").exists())
    assertTrue(staging.list().orEmpty().isEmpty())
  }

  @Test
  fun aBrokenDownloadFailsAndResumesNextTime() = runTest {
    val big = Random(42).nextBytes(200_000)
    val server =
      server(
        ServedFile(1, 51, "scph5501.bin", big, md5(big)),
        ServedFile(2, 51, "scph1001.bin", bios, md5(bios)),
      )
    val installer = FirmwareInstaller(server.client, staging)

    breakAfter = 100_000
    val broken = installer.install(PlatformCatalog.PSX, duckStation, listOf(folder.path))
    val done = assertIs<FirmwareInstall.Done>(broken)
    assertEquals(setOf("scph5501.bin"), done.failed.keys)
    assertEquals(listOf("scph1001.bin"), done.placed, "The broken file doesn't hold up the rest")
    assertFalse(File(folder, "scph5501.bin").exists())
    val kept = staging.listFiles()!!.single().length()
    assertTrue(kept in 1..<big.size, "What arrived is kept")

    breakAfter = null
    assertEquals(
      FirmwareInstall.Done(folder.path, listOf("scph5501.bin"), listOf("scph1001.bin"), emptyMap()),
      installer.install(PlatformCatalog.PSX, duckStation, listOf(folder.path)),
    )
    assertContentEquals(big, File(folder, "scph5501.bin").readBytes())
    assertEquals(
      "bytes=$kept-",
      server.requests.last { it.apiPath.endsWith("/scph5501.bin") }.headers[HttpHeaders.Range],
    )
    assertTrue(staging.list().orEmpty().isEmpty(), "The staged download is cleaned up")
  }

  @Test
  fun doesntResumeOntoAFileThatChanged() = runTest {
    val old = Random(1).nextBytes(200_000)
    val new = Random(2).nextBytes(200_000)
    breakAfter = 100_000
    FirmwareInstaller(server(ServedFile(1, 51, "scph5501.bin", old, md5(old))).client, staging)
      .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))

    breakAfter = null
    val server = server(ServedFile(1, 51, "scph5501.bin", new, md5(new)))
    val result =
      FirmwareInstaller(server.client, staging)
        .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))
    assertEquals(listOf("scph5501.bin"), assertIs<FirmwareInstall.Done>(result).placed)
    assertContentEquals(new, File(folder, "scph5501.bin").readBytes())
    assertNull(server.requests.last().headers[HttpHeaders.Range], "It starts over")
  }

  @Test
  fun skipsFoldersItCantWrite() = runTest {
    // A file where the first folder should be, so it can't be created.
    val blocked = File(root, "blocked").apply { writeText("") }
    val server = server(ServedFile(1, 51, "scph5501.bin", bios, md5(bios)))
    val installer = FirmwareInstaller(server.client, staging)

    val result =
      installer.install(PlatformCatalog.PSX, duckStation, listOf(blocked.path, folder.path))
    assertEquals(folder.path, assertIs<FirmwareInstall.Done>(result).folder)
    assertEquals(
      FirmwareInstall.Unreachable(listOf(blocked.path)),
      installer.install(PlatformCatalog.PSX, duckStation, listOf(blocked.path)),
    )
  }

  @Test
  fun reportsWhenTheServerHasNone() = runTest {
    val server = server(ServedFile(2, 54, "dc_boot.bin", dcBoot, md5(dcBoot)))
    val result =
      FirmwareInstaller(server.client, staging)
        .install(PlatformCatalog.PSX, duckStation, listOf(folder.path))

    assertEquals(FirmwareInstall.NoneOnServer, result)
    assertFalse(folder.exists(), "No folder is made for nothing")
    assertNotNull(server.requests.singleOrNull { it.url.parameters["platform_id"] == "51" })
  }
}
