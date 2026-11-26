// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from argosy-sigil (https://github.com/rommapp/argosy-sigil, MPL-2.0): src/psp.c,
// src/threeds.c, src/switch_nsp.c, src/switch_xci.c and src/wii.c.

package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.saves.GameIdKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Decompresses a zstd stream that starts at an offset in a file, as far as a length, for the 3DS's
 * compressed images. Null when it can't.
 */
fun interface Zstd {
  fun prefix(file: File, offset: Long, length: Int): ByteArray?
}

/**
 * Reads the id a game's saves are filed under from its file, without the emulator: a PSP disc's
 * serial, a 3DS title id, a Switch application id, or a GameCube or Wii game code.
 */
object GameIds {
  /**
   * [file]'s id of [kind], as [dev.sargunv.handheld.domain.saves.SaveFolders.forGame] takes it, or
   * null when the file doesn't carry one the launcher can read.
   *
   * @param switchKeys the emulator's `prod.keys`, which opens a Switch game that carries no ticket.
   * @param zstd opens a compressed 3DS image.
   */
  fun read(file: File, kind: GameIdKind, switchKeys: File? = null, zstd: Zstd? = null): String? =
    try {
      RandomAccessFile(file, "r").use { raf ->
        val image = FileImage(raf)
        when (kind) {
          GameIdKind.Psp -> psp(image)
          GameIdKind.N3ds -> threeDs(file, image, zstd)
          GameIdKind.Switch -> switch(image, switchKeys?.let(::headerKey))
          GameIdKind.Disc -> disc(image)
        }
      }
    } catch (e: java.io.IOException) {
      null
    } catch (e: IndexOutOfBoundsException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    } catch (e: ArithmeticException) {
      null
    } catch (e: java.util.zip.DataFormatException) {
      null
    }

  // PSP

  private fun psp(file: Image): String? {
    val image = if (file.ascii(0, 4) == "CISO") Cso(file) else file
    if (file.ascii(0, 4) == "\u0000PBP") {
      // An eboot's PARAM.SFO is its first section.
      val sfo = file.le32(8).toLong()
      return sfoString(
          file.bytes(sfo, (file.le32(0x0C) - sfo).toInt().coerceIn(0, 0x10000)),
          "DISC_ID",
        )
        ?.takeIf { PSP_SERIAL.matches(it) }
    }
    val iso = Iso(image)
    val umd = iso.find("UMD_DATA.BIN")?.let { image.bytes(it.first, minOf(it.second, 64)) }
    val serial =
      umd?.decodeToString()?.substringBefore('|')
        ?: iso.find("PSP_GAME/PARAM.SFO")?.let {
          sfoString(image.bytes(it.first, it.second), "DISC_ID")
        }
    return serial?.replace("-", "")?.takeIf { PSP_SERIAL.matches(it) }
  }

  /** The string [key] in a PARAM.SFO, or null. */
  private fun sfoString(sfo: ByteArray, key: String): String? {
    val b = Bytes(sfo)
    if (b.ascii(0, 4) != "\u0000PSF") return null
    val keys = b.le32(8)
    val data = b.le32(12)
    for (i in 0 until b.le32(16)) {
      val entry = 20 + i * 16
      val name = b.cString(keys + b.le16(entry))
      if (name == key) return b.cString(data + b.le32(entry + 12), b.le32(entry + 4))
    }
    return null
  }

  /** An ISO 9660 image, enough to find a file by path. */
  private class Iso(private val image: Image) {
    /** The file at [path], as its offset and size, or null. */
    fun find(path: String): Pair<Long, Int>? {
      val pvd = image.bytes(16L * SECTOR, 190)
      if (Bytes(pvd).ascii(1, 5) != "CD001") return null
      var dir = record(pvd, 156)
      for (name in path.split('/')) {
        dir = entries(dir.first, dir.second).firstOrNull { it.first == name }?.second ?: return null
      }
      return dir.first * SECTOR to dir.second
    }

    private fun record(bytes: ByteArray, at: Int): Pair<Long, Int> {
      val b = Bytes(bytes)
      return b.le32(at + 2).toLong() to b.le32(at + 10)
    }

    private fun entries(sector: Long, size: Int): List<Pair<String, Pair<Long, Int>>> {
      val bytes = image.bytes(sector * SECTOR, minOf(size, 1 shl 20))
      val b = Bytes(bytes)
      val found = mutableListOf<Pair<String, Pair<Long, Int>>>()
      var at = 0
      while (at < bytes.size) {
        val length = bytes[at].toInt() and 0xFF
        if (length == 0) {
          // Records don't cross sectors; the rest of this one is padding.
          at = (at / SECTOR + 1) * SECTOR
          continue
        }
        val name = b.ascii(at + 33L, bytes[at + 32].toInt() and 0xFF).substringBefore(';')
        found += name to record(bytes, at)
        at += length
      }
      return found
    }
  }

  /** A CSO (compressed ISO) image, version 1: blocks of raw deflate, or stored. */
  private class Cso(private val file: Image) : Image {
    private val blockSize = file.le32(0x10)
    private val align = file.bytes(0x15, 1)[0].toInt()

    override fun bytes(offset: Long, length: Int): ByteArray {
      val out = ByteArrayOutputStream(length)
      var at = offset
      while (out.size() < length) {
        val block = at / blockSize
        val data = block(block)
        val skip = (at - block * blockSize).toInt()
        val take = minOf(length - out.size(), data.size - skip)
        if (take <= 0) throw java.io.IOException("The image ends early")
        out.write(data, skip, take)
        at += take
      }
      return out.toByteArray()
    }

    private fun block(n: Long): ByteArray {
      val index = file.bytes(0x18 + n * 4, 8)
      val start = Bytes(index).le32(0).toLong() and 0xFFFFFFFFL
      val end = Bytes(index).le32(4).toLong() and 0x7FFFFFFFL
      val stored = start and 0x80000000L != 0L
      val from = (start and 0x7FFFFFFFL) shl align
      val raw = file.bytes(from, ((end shl align) - from).toInt().coerceAtMost(blockSize * 2))
      if (stored) return raw.copyOf(minOf(raw.size, blockSize))
      return inflate(raw, blockSize, nowrap = true)
    }
  }

  // 3DS

  private fun threeDs(file: File, raw: Image, zstd: Zstd?): String? {
    val image: Image =
      if (raw.ascii(0, 4) == "Z3DS") {
        val header = Bytes(raw.bytes(0, 0x20))
        if (header.bytes[8].toInt() != 1) return null
        val payload = header.le16(0x0A).toLong() + header.le32(0x0C)
        Bytes(zstd?.prefix(file, payload, Z3DS_PREFIX) ?: return null)
      } else {
        raw
      }
    val ncch =
      when {
        image.ascii(0x100, 4) == "NCSD" -> image.le32(0x120).toLong() * 0x200
        image.ascii(0x100, 4) == "NCCH" -> 0L
        image.le32(0) == 0x2020 -> ciaContent(image) ?: return null
        else -> return null
      }
    if (image.ascii(ncch + 0x100, 4) != "NCCH") return null
    val id = "%016X".format(Bytes(image.bytes(ncch + 0x118, 8)).le64(0))
    // Retail titles only: system titles and homebrew keep their saves elsewhere.
    return id.takeIf { it.startsWith("0004") }
  }

  /** Where a CIA's first content starts: each section begins on a 64-byte boundary. */
  private fun ciaContent(image: Image): Long? {
    val head = Bytes(image.bytes(0, 0x20))
    var at = 0L
    for (size in listOf(head.le32(0), head.le32(0x08), head.le32(0x0C), head.le32(0x10))) {
      at = (at + size + 63) and 63L.inv()
    }
    return at
  }

  // Switch

  private fun switch(image: Image, headerKey: ByteArray?): String? {
    val files =
      when {
        image.ascii(0, 4) == "PFS0" -> pfs0(image, 0)
        image.ascii(0x100, 4) == "HEAD" -> {
          val root = hfs0(image, image.le64(0x130))
          val secure = root.firstOrNull { it.name == "secure" } ?: return null
          hfs0(image, secure.offset)
        }
        else -> return null
      }
    // With keys, the NCAs' headers decide; without, or when the keys don't open them, the tickets.
    val ids =
      headerKey
        ?.let { key ->
          files
            .filter { it.name.endsWith(".nca") || it.name.endsWith(".ncz") }
            .mapNotNull { ncaTitle(image, it.offset, key) }
        }
        .orEmpty()
        .ifEmpty {
          files.filter { it.name.endsWith(".tik") }.mapNotNull { ticketTitle(image, it.offset) }
        }
    return ids.map(::applicationOf).distinct().singleOrNull()?.let { "%016X".format(it) }
  }

  private class Entry(val name: String, val offset: Long)

  /** A PFS0 partition's files, the NSP's container, at [base]. */
  private fun pfs0(image: Image, base: Long): List<Entry> = partition(image, base, entrySize = 0x18)

  /** An HFS0 partition's files, the XCI's container, at [base]. */
  private fun hfs0(image: Image, base: Long): List<Entry> {
    if (image.ascii(base, 4) != "HFS0") return emptyList()
    return partition(image, base, entrySize = 0x40)
  }

  private fun partition(image: Image, base: Long, entrySize: Int): List<Entry> {
    val head = Bytes(image.bytes(base, 16))
    val count = head.le32(4)
    val strings = head.le32(8)
    if (count !in 0..4096) return emptyList()
    val table = Bytes(image.bytes(base + 16, count * entrySize + strings))
    val data = base + 16 + count * entrySize + strings
    return (0 until count).map {
      val entry = it * entrySize
      Entry(table.cString(count * entrySize + table.le32(entry + 16)), data + table.le64(entry))
    }
  }

  /** The title id a ticket's rights id starts with, or null for a ticket it can't read. */
  private fun ticketTitle(image: Image, offset: Long): Long? {
    val signature =
      when (Bytes(image.bytes(offset, 4)).le32(0)) {
        0x10000,
        0x10003 -> 0x200 + 0x3C
        0x10001,
        0x10004 -> 0x100 + 0x3C
        0x10002,
        0x10005 -> 0x3C + 0x40
        else -> return null
      }
    val rights = Bytes(image.bytes(offset + 4 + signature + 0x160, 16))
    return rights.be64(0).takeIf { it != 0L }
  }

  /** The program id in an NCA's header, which [headerKey] decrypts. */
  private fun ncaTitle(image: Image, offset: Long, headerKey: ByteArray): Long? {
    // The magic and the program id sit in the header's second 0x200-byte sector.
    val sector = xtsDecrypt(image.bytes(offset + 0x200, 0x20), headerKey, sector = 1)
    val header = Bytes(sector)
    if (header.ascii(0, 3) != "NCA") return null
    // A game's program id starts with 01.
    return header.le64(0x10).takeIf { it ushr 56 == 1L }
  }

  /**
   * The game an update or add-on belongs to, whose id its saves are filed under. An update's id
   * ends in `800`, its game's in `000`; an add-on's game is the block below its own.
   */
  private fun applicationOf(id: Long): Long =
    when (id and 0xFFF) {
      0L -> id
      0x800L -> id and 0xFFFL.inv()
      else -> (id and 0xFFFL.inv()) - 0x1000
    }

  /** The `header_key` from a `prod.keys` file. */
  private fun headerKey(keys: File): ByteArray? =
    try {
      keys
        .readLines()
        .firstNotNullOfOrNull { line ->
          val (name, value) =
            line.split('=').map { it.trim() }.takeIf { it.size == 2 }
              ?: return@firstNotNullOfOrNull null
          value.takeIf { name == "header_key" && it.length == 64 }
        }
        ?.chunked(2)
        ?.map { it.toInt(16).toByte() }
        ?.toByteArray()
    } catch (e: java.io.IOException) {
      null
    } catch (e: NumberFormatException) {
      null
    }

  /**
   * Decrypts the start of one sector with AES-128-XTS, as the Switch encrypts NCA headers: the
   * sector number is the tweak, big-endian.
   */
  internal fun xtsDecrypt(data: ByteArray, key: ByteArray, sector: Long): ByteArray =
    xts(data, key, sector, Cipher.DECRYPT_MODE)

  internal fun xts(data: ByteArray, key: ByteArray, sector: Long, mode: Int): ByteArray {
    val crypt = Cipher.getInstance("AES/ECB/NoPadding")
    crypt.init(mode, SecretKeySpec(key, 0, 16, "AES"))
    val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding")
    tweakCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 16, 16, "AES"))
    val tweak = ByteArray(16)
    for (i in 0 until 8) tweak[15 - i] = (sector shr (8 * i)).toByte()
    val t = tweakCipher.doFinal(tweak)
    val out = ByteArray(data.size)
    for (block in 0 until data.size / 16) {
      val x = ByteArray(16) { (data[block * 16 + it].toInt() xor t[it].toInt()).toByte() }
      val y = crypt.doFinal(x)
      for (i in 0 until 16) out[block * 16 + i] = (y[i].toInt() xor t[i].toInt()).toByte()
      // The tweak times x in GF(2^128), little-endian.
      var carry = 0
      for (i in 0 until 16) {
        val v = t[i].toInt() and 0xFF
        t[i] = ((v shl 1) or carry).toByte()
        carry = v shr 7
      }
      if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }
    return out
  }

  // GameCube and Wii

  private fun disc(image: Image): String? {
    val header: Long =
      when {
        image.ascii(0, 4) == "RVZ\u0001" || image.ascii(0, 4) == "WIA\u0001" -> 0x58
        image.ascii(0, 4) == "WBFS" -> {
          val shift = image.bytes(8, 1)[0].toInt()
          if (shift !in 9..16) return null
          1L shl shift
        }
        // A GameCube CISO: a map of used blocks, with the first block after it.
        image.ascii(0, 4) == "CISO" -> 0x8000
        image.le32(0) == 0xB10BC001.toInt() -> return discHeader(Bytes(gczFirstBlock(image)))
        else -> 0
      }
    return discHeader(Bytes(image.bytes(header, 0x20)))
  }

  /** The game code in a disc header, checked against its GameCube or Wii magic. */
  private fun discHeader(header: Image): String? {
    val wii = header.be32(0x18) == 0x5D1C9EA3
    val gameCube = header.be32(0x1C) == 0xC2339F3D.toInt()
    if (!wii && !gameCube) return null
    return header.ascii(0, 4).takeIf { DISC_CODE.matches(it) }
  }

  /** The first block of a GCZ image, which Dolphin compresses with zlib. */
  private fun gczFirstBlock(image: Image): ByteArray {
    val head = Bytes(image.bytes(0, 32))
    val blockSize = head.le32(24)
    val blocks = head.le32(28)
    val pointers = Bytes(image.bytes(32, 16))
    val first = pointers.le64(0)
    // The last block ends where the compressed data does.
    val next = if (blocks > 1) pointers.le64(8) else head.le64(8)
    val data = 32L + blocks.toLong() * 12
    val stored = first < 0
    val start = first and Long.MAX_VALUE
    val raw =
      image.bytes(
        data + start,
        ((next and Long.MAX_VALUE) - start).toInt().coerceIn(0, blockSize * 2),
      )
    return if (stored) raw else inflate(raw, blockSize, nowrap = false)
  }

  private fun inflate(raw: ByteArray, size: Int, nowrap: Boolean): ByteArray {
    val inflater = Inflater(nowrap)
    try {
      inflater.setInput(raw)
      val out = ByteArray(size)
      var n = 0
      while (n < size && !inflater.finished()) {
        val got = inflater.inflate(out, n, size - n)
        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        n += got
      }
      return out.copyOf(n)
    } finally {
      inflater.end()
    }
  }

  // Reading

  /** Random access to an image's bytes. */
  private interface Image {
    /** [length] bytes from [offset]; fails when the image ends sooner. */
    fun bytes(offset: Long, length: Int): ByteArray

    fun ascii(offset: Long, length: Int): String =
      try {
        bytes(offset, length).decodeToString()
      } catch (e: java.io.IOException) {
        ""
      } catch (e: IndexOutOfBoundsException) {
        ""
      }

    fun le16(offset: Int): Int = Bytes(bytes(offset.toLong(), 2)).le16(0)

    fun le32(offset: Long): Int = Bytes(bytes(offset, 4)).le32(0)

    fun le64(offset: Long): Long = Bytes(bytes(offset, 8)).le64(0)

    fun be32(offset: Int): Int = Bytes(bytes(offset.toLong(), 4)).be32(0)
  }

  private class FileImage(private val raf: RandomAccessFile) : Image {
    override fun bytes(offset: Long, length: Int): ByteArray {
      require(offset >= 0 && length >= 0)
      val out = ByteArray(length)
      raf.seek(offset)
      raf.readFully(out)
      return out
    }
  }

  private class Bytes(val bytes: ByteArray) : Image {
    override fun bytes(offset: Long, length: Int): ByteArray {
      if (offset < 0 || offset + length > bytes.size) throw IndexOutOfBoundsException()
      return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun u(i: Int) = bytes[i].toLong() and 0xFF

    override fun le16(offset: Int): Int = (u(offset) or (u(offset + 1) shl 8)).toInt()

    fun le32(offset: Int): Int =
      (0 until 4).fold(0L) { acc, i -> acc or (u(offset + i) shl (8 * i)) }.toInt()

    fun le64(offset: Int): Long =
      (0 until 8).fold(0L) { acc, i -> acc or (u(offset + i) shl (8 * i)) }

    override fun be32(offset: Int): Int =
      (0 until 4).fold(0L) { acc, i -> (acc shl 8) or u(offset + i) }.toInt()

    fun be64(offset: Int): Long = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or u(offset + i) }

    fun cString(offset: Int, max: Int = bytes.size - offset): String {
      val end =
        (offset until minOf(bytes.size, offset + max)).firstOrNull { bytes[it] == 0.toByte() }
          ?: minOf(bytes.size, offset + max)
      return bytes.copyOfRange(offset, end).decodeToString()
    }
  }

  private const val SECTOR = 2048
  private const val Z3DS_PREFIX = 0x10000
  private val PSP_SERIAL = Regex("[A-Z]{4}[0-9]{5}")
  private val DISC_CODE = Regex("[A-Z0-9]{4}")
}
