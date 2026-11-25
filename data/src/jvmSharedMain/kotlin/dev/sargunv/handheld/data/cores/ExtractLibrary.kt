package dev.sargunv.handheld.data.cores

import java.io.EOFException
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.io.files.Path

internal actual fun extractLibrary(zip: Path, target: Path) {
  try {
    ZipInputStream(File(zip.toString()).inputStream().buffered()).use { input ->
      while (true) {
        val entry = input.nextEntry ?: throw CoreDownloadException("The zip holds no library")
        if (entry.isDirectory || !entry.name.endsWith(".so")) continue
        // Reading the entry to its end checks it against its CRC.
        File(target.toString()).outputStream().use { input.copyTo(it) }
        return
      }
    }
  } catch (e: ZipException) {
    throw CoreDownloadException("The zip is broken: ${e.message}", e)
  } catch (e: EOFException) {
    throw CoreDownloadException("The zip is cut short", e)
  }
}
