package dev.sargunv.handheld.data.cores

import co.touchlab.kermit.Logger
import java.io.EOFException
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Extracts the core's library, the zip's one `.so` file, from [zip] into [target], checking each
 * byte against the zip's checksum.
 *
 * @throws CoreDownloadException when the zip is broken or holds no library.
 */
internal fun extractLibrary(zip: File, target: File) {
  try {
    ZipInputStream(zip.inputStream().buffered()).use { input ->
      while (true) {
        val entry = input.nextEntry ?: throw CoreDownloadException("No core in download")
        if (entry.isDirectory || !entry.name.endsWith(".so")) continue
        // Reading the entry to its end checks it against its CRC.
        target.outputStream().use { input.copyTo(it) }
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
