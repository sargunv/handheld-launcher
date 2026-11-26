// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from argosy-sigil (https://github.com/rommapp/argosy-sigil, MPL-2.0): src/switch_nsp.c
// and src/switch_xci.c.

package dev.sargunv.handheld.data.saves

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the application id a Switch game's saves are filed under from the game's own containers,
 * without the emulator.
 */
object GameIds {
  /**
   * [file]'s application id, as [dev.sargunv.handheld.domain.saves.SaveFolders.forGame] takes it,
   * or null when the file doesn't carry one the launcher can read. [keys], the emulator's
   * `prod.keys`, opens a game that carries no ticket.
   */
  fun switchTitle(file: File, keys: File? = null): String? =
    try {
      RandomAccessFile(file, "r").use { switch(Image(it), keys?.let(::headerKey)) }
    } catch (e: IOException) {
      null
    } catch (e: IndexOutOfBoundsException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }

  private fun switch(image: Image, headerKey: ByteArray?): String? {
    val files =
      when {
        image.ascii(0, 4) == "PFS0" -> partition(image, 0, entrySize = 0x18)
        image.ascii(0x100, 4) == "HEAD" -> {
          val root = hfs0(image, image.bytes(0x130, 8).getLong(0))
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

  /** An HFS0 partition's files, the XCI's container, at [base]. */
  private fun hfs0(image: Image, base: Long): List<Entry> {
    if (image.ascii(base, 4) != "HFS0") return emptyList()
    return partition(image, base, entrySize = 0x40)
  }

  /** The files of a PFS0 partition, the NSP's container, or an HFS0 one, at [base]. */
  private fun partition(image: Image, base: Long, entrySize: Int): List<Entry> {
    val head = image.bytes(base, 16)
    val count = head.getInt(4)
    val strings = head.getInt(8)
    if (count !in 0..4096) return emptyList()
    val table = image.bytes(base + 16, count * entrySize + strings)
    val data = base + 16 + count * entrySize + strings
    return (0 until count).map {
      val entry = it * entrySize
      Entry(
        table.cString(count * entrySize + table.getInt(entry + 16)),
        data + table.getLong(entry),
      )
    }
  }

  /** The title id a ticket's rights id starts with, or null for a ticket it can't read. */
  private fun ticketTitle(image: Image, offset: Long): Long? {
    val signature =
      when (image.bytes(offset, 4).getInt(0)) {
        0x10000,
        0x10003 -> 0x200 + 0x3C
        0x10001,
        0x10004 -> 0x100 + 0x3C
        0x10002,
        0x10005 -> 0x3C + 0x40
        else -> return null
      }
    val rights = image.bytes(offset + 4 + signature + 0x160, 8).order(ByteOrder.BIG_ENDIAN)
    return rights.getLong(0).takeIf { it != 0L }
  }

  /** An NCA's content type for a program, as opposed to an add-on's data. */
  private const val NCA_PROGRAM = 0

  /**
   * The program id in an NCA's header, which [headerKey] decrypts. A game made of several programs
   * numbers them in the id's last digit, as yuzu's `GetBaseTitleIDWithProgramIndex` does, so a
   * program's id is taken back to its first program's. An add-on's id can end the same way, so only
   * a program's is.
   */
  private fun ncaTitle(image: Image, offset: Long, headerKey: ByteArray): Long? {
    // The magic, content type and program id sit in the header's second 0x200-byte sector.
    val sector = xtsDecrypt(image.bytes(offset + 0x200, 0x20).array(), headerKey, sector = 1)
    if (sector.copyOf(3).decodeToString() != "NCA") return null
    // A game's program id starts with 01.
    val id = littleEndian(sector).getLong(0x10).takeIf { it ushr 56 == 1L } ?: return null
    val isProgram = sector[5].toInt() == NCA_PROGRAM
    return if (isProgram && (id and 0xFFF) in 1L..0xFL) id and 0xFL.inv() else id
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
    } catch (e: IOException) {
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

  /** A game's file, read at offsets. */
  private class Image(private val raf: RandomAccessFile) {
    /** [length] bytes from [offset], little-endian; fails when the file ends sooner. */
    fun bytes(offset: Long, length: Int): ByteBuffer {
      require(offset >= 0 && length >= 0)
      val out = ByteArray(length)
      raf.seek(offset)
      raf.readFully(out)
      return littleEndian(out)
    }

    fun ascii(offset: Long, length: Int): String = bytes(offset, length).array().decodeToString()
  }

  private fun littleEndian(bytes: ByteArray): ByteBuffer =
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  /** The NUL-terminated string at [offset]. */
  private fun ByteBuffer.cString(offset: Int): String {
    val bytes = array()
    val end = (offset until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: bytes.size
    return bytes.copyOfRange(offset, end).decodeToString()
  }
}
