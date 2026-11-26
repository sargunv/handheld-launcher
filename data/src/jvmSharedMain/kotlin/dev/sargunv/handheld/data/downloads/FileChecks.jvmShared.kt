package dev.sargunv.handheld.data.downloads

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.io.files.Path

internal actual fun matchesServerHashes(
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
  fun matches(hash: Hash) = hash.hex().equals(expected, ignoreCase = true)

  val file = File(path.toString())
  if (matches(Hash(algorithm).also { file.inputStream().use(it::update) })) return true
  val zip =
    try {
      ZipFile(file)
    } catch (e: ZipException) {
      // Not a zip. RomM hashes other archives' content too, which this check can't read.
      // A file still downloading has a `.part` suffix after its own extension.
      val extension = file.name.removeSuffix(".part").substringAfterLast('.', "").lowercase()
      return if (extension in OtherArchives) null else false
    }
  // A ROM that's one zip has the hash of its members' content in name order, and a zip among a
  // ROM's files has the hash of its largest member.
  return try {
    zip.use {
      val whole = Hash(algorithm)
      var largest: Pair<Long, Hash>? = null
      val members = zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }
      for (entry in members) {
        val member = Hash(algorithm)
        zip.getInputStream(entry).use { input ->
          input.forEachChunk { buffer, count ->
            whole.update(buffer, count)
            member.update(buffer, count)
          }
        }
        if (largest == null || entry.size > largest.first) largest = entry.size to member
      }
      matches(whole) || largest?.second?.let(::matches) == true
    }
  } catch (e: IOException) {
    // A compression method Java doesn't read, such as Deflate64.
    null
  }
}

internal actual fun freeBytes(root: String): Long = File(root).usableSpace

internal actual fun lastModified(path: Path): Long = File(path.toString()).lastModified()

internal actual fun zipMembers(path: Path): List<ZipMember>? =
  try {
    ZipFile(File(path.toString())).use { zip ->
      zip
        .entries()
        .asSequence()
        .filterNot { it.isDirectory }
        .map { ZipMember(it.name, it.size, it.crc.toString(16).padStart(8, '0')) }
        .toList()
    }
  } catch (e: ZipException) {
    null
  }

internal actual fun crcAndSha1(path: Path): Pair<String, String> {
  val crc = Hash("CRC32")
  val sha1 = Hash("SHA-1")
  File(path.toString()).inputStream().use { input ->
    input.forEachChunk { buffer, count ->
      crc.update(buffer, count)
      sha1.update(buffer, count)
    }
  }
  return crc.hex() to sha1.hex()
}

private val OtherArchives = setOf("7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

/** A running hash with the algorithm RomM names, which [MessageDigest] lacks for CRC-32. */
private class Hash(algorithm: String) {
  private val crc = if (algorithm == "CRC32") CRC32() else null
  private val digest = if (crc == null) MessageDigest.getInstance(algorithm) else null

  fun update(buffer: ByteArray, count: Int) {
    crc?.update(buffer, 0, count)
    digest?.update(buffer, 0, count)
  }

  fun update(input: InputStream) = input.forEachChunk(::update)

  fun hex(): String =
    crc?.value?.toString(16)?.padStart(8, '0')
      ?: digest!!.digest().joinToString("") { "%02x".format(it) }
}

private inline fun InputStream.forEachChunk(block: (ByteArray, Int) -> Unit) {
  val buffer = ByteArray(64 * 1024)
  while (true) {
    val count = read(buffer)
    if (count < 0) break
    block(buffer, count)
  }
}
