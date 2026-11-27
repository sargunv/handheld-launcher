package dev.sargunv.handheld.data

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/** The digest named [algorithm]: one [MessageDigest] has, or `CRC32`, which RomM also uses. */
internal fun digest(algorithm: String): MessageDigest =
  if (algorithm == "CRC32") Crc32Digest() else MessageDigest.getInstance(algorithm)

/** The [algorithm] digest of [bytes], in lowercase hex. */
internal fun hexDigest(algorithm: String, bytes: ByteArray): String =
  digest(algorithm).digest(bytes).toHexString()

/** The [algorithm] digest of [file]'s bytes, in lowercase hex. */
internal fun hexDigest(algorithm: String, file: File): String {
  val digest = digest(algorithm)
  file.inputStream().use { it.feed(listOf(digest)) }
  return digest.digest().toHexString()
}

/** Feeds the rest of this stream to each of [digests], 64 KiB at a time. */
internal fun InputStream.feed(digests: List<MessageDigest>) {
  val buffer = ByteArray(64 * 1024)
  while (true) {
    val count = read(buffer)
    if (count < 0) break
    for (digest in digests) digest.update(buffer, 0, count)
  }
}

private class Crc32Digest : MessageDigest("CRC32") {
  private val crc = CRC32()

  override fun engineUpdate(input: Byte) = crc.update(input.toInt())

  override fun engineUpdate(input: ByteArray, offset: Int, len: Int) =
    crc.update(input, offset, len)

  override fun engineDigest(): ByteArray =
    ByteBuffer.allocate(4).putInt(crc.value.toInt()).array().also { crc.reset() }

  override fun engineReset() = crc.reset()
}
