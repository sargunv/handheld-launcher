package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.digest
import dev.sargunv.handheld.data.feed
import dev.sargunv.handheld.data.hexDigests
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.io.files.Path

/**
 * Whether the file at [path] matches the hashes RomM lists for it, by the first of MD5, SHA-1 and
 * CRC-32 that RomM has. RomM hashes an archive's content rather than the archive itself, so a zip
 * also matches by its content. Null when it can't tell: RomM lists no hash, or the file is an
 * archive this check can't read.
 */
internal fun matchesServerHashes(
  path: Path,
  md5: String?,
  sha1: String?,
  crc: String?,
): Boolean? {
  val (algorithm, expected) =
    when {
      md5 != null -> "MD5" to md5
      sha1 != null -> "SHA-1" to sha1
      crc != null -> "CRC32" to crc.padStart(8, '0')
      else -> return null
    }
  fun matches(hex: String) = hex.equals(expected, ignoreCase = true)

  val file = File(path.toString())
  if (matches(hexDigests(file, listOf(algorithm)).single())) return true
  val zip =
    try {
      ZipFile(file)
    } catch (e: ZipException) {
      // Not a zip. RomM hashes other archives' content too, which this check can't read.
      val extension = file.extension.lowercase()
      return if (extension in OtherArchives) null else false
    }
  // A ROM that's one zip has the hash of its members' content in name order, and a zip among a
  // ROM's files has the hash of its largest member.
  return try {
    zip.use {
      val whole = digest(algorithm)
      var largest: Pair<Long, String>? = null
      val members = zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }
      for (entry in members) {
        val member = digest(algorithm)
        zip.getInputStream(entry).use { it.feed(listOf(whole, member)) }
        if (largest == null || entry.size > largest.first) {
          largest = entry.size to member.digest().toHexString()
        }
      }
      matches(whole.digest().toHexString()) || largest?.second?.let(::matches) == true
    }
  } catch (e: IOException) {
    // A compression method Java doesn't read, such as Deflate64.
    null
  }
}

/** The bytes the app can write to the storage at [root]. */
internal fun freeBytes(root: String): Long = File(root).usableSpace

/** When the file at [path] last changed, in milliseconds since the epoch. */
internal fun lastModified(path: Path): Long = File(path.toString()).lastModified()

/**
 * The CRC-32 of the one file in the zip at [path], from its central directory, in lowercase hex.
 * Null when it isn't a zip this can read or holds other than one file.
 */
internal fun oneFileZipCrc(path: Path): String? =
  try {
    ZipFile(File(path.toString())).use { zip ->
      zip
        .entries()
        .asSequence()
        .filterNot { it.isDirectory }
        .singleOrNull()
        ?.crc
        ?.toString(16)
        ?.padStart(8, '0')
    }
  } catch (e: ZipException) {
    null
  }

/** The CRC-32 and SHA-1 of the whole file at [path], in lowercase hex. */
internal fun crcAndSha1(path: Path): Pair<String, String> {
  val (crc, sha1) = hexDigests(File(path.toString()), listOf("CRC32", "SHA-1"))
  return crc to sha1
}

private val OtherArchives = setOf("7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
