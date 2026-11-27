package dev.sargunv.handheld.data.cores

import co.touchlab.kermit.Logger
import java.io.EOFException
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.io.files.Path

internal actual fun extractLibrary(zip: Path, target: Path) {
  try {
    ZipInputStream(File(zip.toString()).inputStream().buffered()).use { input ->
      while (true) {
        val entry = input.nextEntry ?: throw CoreDownloadException("No core in download")
        if (entry.isDirectory || !entry.name.endsWith(".so")) continue
        // Reading the entry to its end checks it against its CRC.
        File(target.toString()).outputStream().use { input.copyTo(it) }
        return
      }
    }
  } catch (e: ZipException) {
    log.w(e) { "Damaged core zip $zip" }
    throw CoreDownloadException("Core zip damaged", e)
  } catch (e: EOFException) {
    log.w(e) { "Core zip $zip cut short" }
    throw CoreDownloadException("Core download cut short", e)
  }
}

private val log = Logger.withTag("ExtractLibrary")
