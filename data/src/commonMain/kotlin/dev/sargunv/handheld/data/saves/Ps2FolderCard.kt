// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from argosy-sigil (https://github.com/rommapp/argosy-sigil, MPL-2.0): the PCSX2 folder
// cards in src/card_ps2.c, so a save folder reads and writes as PCSX2 and sigil keep it.

package dev.sargunv.handheld.data.saves

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * PCSX2's folder cards: a directory named as the card file would be, holding `_pcsx2_superblock`
 * and a directory per save folder. A save folder holds the save's files, an `_pcsx2_index` with
 * each file's order and times, and `_pcsx2_meta/` entries for what the index can't say
 * (MemoryCardFolder.cpp).
 */
internal object Ps2FolderCard {
  const val SUPERBLOCK = "_pcsx2_superblock"
  const val SUPERBLOCK_SIZE = 0x2000
  private const val INDEX = "_pcsx2_index"
  private const val META_DIRECTORY = "_pcsx2_meta_directory"
  private const val META_PREFIX = "_pcsx2_meta/"
  private const val RESERVED = "_pcsx2_"
  private const val DIR_MODE = 0x8427
  private const val FILE_MODE = 0x8497

  /** The `_pcsx2_superblock` of an empty 8 MB folder card. */
  fun superblock(): ByteArray = ByteArray(SUPERBLOCK_SIZE).also { Ps2Card.writeSuperblock(it, 0) }

  /**
   * Whether PCSX2 reads [data] as a formatted card's superblock: all 0x2000 bytes there, and byte
   * 0x16 the `o` of "Format". It shows no saves on a card whose superblock isn't.
   */
  fun superblockUsable(data: ByteArray?): Boolean =
    data != null && data.size >= SUPERBLOCK_SIZE && data[0x16] == 'o'.code.toByte()

  /**
   * [save] as the files of a PCSX2 save folder, by path inside it: its files, `_pcsx2_meta` entries
   * for any entry PCSX2 couldn't rebuild from the index alone, and the `_pcsx2_index`. Card dates
   * convert to Unix seconds as UTC, as PCSX2 converts them.
   */
  fun unpack(save: Ps2Save): List<Pair<String, ByteArray>> {
    val out = mutableListOf<Pair<String, ByteArray>>()
    val index = StringBuilder()
    index.append("\$ROOT:\n  timeCreated: ${todToUnix(save.entry, CREATED)}\n")
    index.append("  timeModified: ${todToUnix(save.entry, MODIFIED)}\n")
    for ((i, file) in save.files.withIndex()) {
      val (name, cleaned) = hostName(entryName(file.entry))
      if (!plainName(name) || name.startsWith(RESERVED) || out.any { it.first == name }) {
        throw Ps2FormatException(
          "The save ${save.name} holds a file named $name, which PCSX2 can't keep",
        )
      }
      out += name to file.data.copyOf(u32(file.entry, LENGTH).toInt())
      if (cleaned || !rebuildsWithoutMeta(file.entry, FILE_MODE)) {
        out += "$META_PREFIX$name" to file.entry.copyOf()
      }
      index.append("${quotedKey(name)}:\n  order: ${i + 1}\n")
      index.append("  timeCreated: ${todToUnix(file.entry, CREATED)}\n")
      index.append("  timeModified: ${todToUnix(file.entry, MODIFIED)}\n")
    }
    if (!rebuildsWithoutMeta(save.entry, DIR_MODE)) out += META_DIRECTORY to save.entry.copyOf()
    out += INDEX to index.toString().toByteArray(Charsets.ISO_8859_1)
    return out
  }

  /**
   * The save folder [folder] with [files], by path inside it, as PCSX2 builds it onto its card:
   * entries in the index's order, files it doesn't name first, times from the index, and
   * `_pcsx2_meta` entries over what it gives. Throws [Ps2FormatException] when the index doesn't
   * parse, or a file can't be on a card: a subfolder, or a name too long for an entry.
   */
  fun pack(folder: String, files: List<Pair<String, ByteArray>>): Ps2Save {
    if (!plainName(folder) || folder.length > NAME_LENGTH) {
      throw Ps2FormatException("$folder can't be a save folder's name on a card")
    }
    val byPath = files.toMap()
    val index =
      byPath[INDEX]?.let {
        parseIndex(String(it, Charsets.ISO_8859_1))
          ?: throw Ps2FormatException("The index of the save $folder doesn't read")
      } ?: emptyList()
    fun row(name: String) = index.firstOrNull { it.name == name }
    val root = row("\$ROOT") ?: row("%ROOT")

    var legacyOrder = -1L
    val items =
      files
        .withIndex()
        .filter { !it.value.first.startsWith(RESERVED) }
        .map { (given, file) ->
          val (path, _) = file
          if (!plainName(path) || path.length > NAME_LENGTH) {
            throw Ps2FormatException("The save $folder holds $path, which can't be on a card")
          }
          Triple(file, row(path)?.order ?: legacyOrder--, given)
        }
        .sortedWith(compareBy({ it.second }, { it.third }))

    val packed = items.map { (file, _, _) ->
      val (path, data) = file
      val row = row(path)
      val entry =
        entryFromMeta(
          byPath["$META_PREFIX$path"],
          path,
          FILE_MODE,
          row?.created ?: 0,
          row?.modified ?: 0,
        )
      putU32(entry, LENGTH, data.size.toLong())
      putU32(entry, CLUSTER, 0)
      putU32(entry, PARENT_INDEX, 0)
      Ps2File(entry, data.copyOf())
    }
    val entry =
      entryFromMeta(
        byPath[META_DIRECTORY],
        folder,
        DIR_MODE,
        root?.created ?: 0,
        root?.modified ?: 0,
      )
    putU32(entry, LENGTH, packed.size + 2L)
    val self = defaultEntry(DIR_MODE, ".", 0, 0).also { it.fill(0, CREATED, CREATED + TOD_SIZE) }
    self.fill(0, MODIFIED, MODIFIED + TOD_SIZE)
    val parent = defaultEntry(DIR_MODE, "..", 0, 0).also { it.fill(0, CREATED, CREATED + TOD_SIZE) }
    parent.fill(0, MODIFIED, MODIFIED + TOD_SIZE)
    return Ps2Save(entry, self, parent, packed)
  }

  /**
   * The name PCSX2 gives card entry [name] on the host, and whether it differs: `\ % : | " < >`
   * become `_`, and so do the dots and spaces that end it (CleanMemcardFilename).
   */
  private fun hostName(name: String): Pair<String, Boolean> {
    val out = name.map { if (it in "\\%:|\"<>") '_' else it }.toCharArray()
    var n = out.size
    while (n > 0 && (out[n - 1] == ' ' || out[n - 1] == '.')) out[--n] = '_'
    val host = String(out)
    return host to (host != name)
  }

  /** The entry PCSX2 builds for a file or folder it has no meta for. */
  private fun defaultEntry(mode: Int, name: String, created: Long, modified: Long): ByteArray {
    val e = ByteArray(ENTRY_SIZE)
    putU16(e, MODE, mode)
    unixToTod(created, e, CREATED)
    unixToTod(modified, e, MODIFIED)
    name.toByteArray(Charsets.ISO_8859_1).copyInto(e, NAME)
    return e
  }

  /** Whether PCSX2 rebuilds [actual] from the index alone, but for what it fills in itself. */
  private fun rebuildsWithoutMeta(actual: ByteArray, mode: Int): Boolean {
    val expect =
      defaultEntry(mode, entryName(actual), todToUnix(actual, CREATED), todToUnix(actual, MODIFIED))
    val have = actual.copyOf()
    have.fill(0, LENGTH, LENGTH + 4)
    have.fill(0, CLUSTER, CLUSTER + 8)
    return expect.contentEquals(have)
  }

  /**
   * The entry PCSX2 builds for [name]: its default, with the bytes of the meta file [meta] read
   * over it. A full meta names the entry when its name is the one PCSX2 wrote [name] for, such as
   * "1: Home" for the host file "1_ Home"; otherwise the host's name does, so a meta can't move one
   * file's entry onto another's name.
   */
  private fun entryFromMeta(
    meta: ByteArray?,
    name: String,
    mode: Int,
    created: Long,
    modified: Long,
  ): ByteArray {
    val e = defaultEntry(mode, name, created, modified)
    meta?.copyInto(e, 0, 0, minOf(meta.size, ENTRY_SIZE))
    val cardName = entryName(e)
    if (
      meta != null &&
        meta.size >= NAME + NAME_LENGTH &&
        cardName.isNotEmpty() &&
        hostName(cardName).first == name
    ) {
      return e
    }
    e.fill(0, NAME, NAME + NAME_LENGTH)
    name.toByteArray(Charsets.ISO_8859_1).copyInto(e, NAME)
    return e
  }

  /** A YAML single-quoted key: quotes inside it double. */
  private fun quotedKey(name: String) = "'" + name.replace("'", "''") + "'"

  // The index

  private class Row(val name: String) {
    var order: Long? = null
    var created: Long? = null
    var modified: Long? = null

    fun set(field: String, value: Long) {
      when (field) {
        "order" -> order = value
        "timeCreated" -> created = value
        "timeModified" -> modified = value
      }
    }
  }

  /** The rows of an `_pcsx2_index`, in block or flow YAML, or null when it doesn't parse. */
  private fun parseIndex(text: String): List<Row>? {
    val rows = mutableListOf<Row>()
    val reader = Reader(text)
    reader.skipSpace()
    val ok = if (reader.peek() == '{') parseFlow(reader, rows) else parseBlock(text, rows)
    return rows.takeIf { ok }
  }

  private fun newRow(rows: MutableList<Row>, key: String) =
    Row(key.take(NAME_LENGTH)).also { rows += it }

  private fun parseFlow(r: Reader, rows: MutableList<Row>): Boolean {
    r.skipSpace()
    if (r.next() != '{') return false
    while (true) {
      r.skipSpace()
      if (r.peek() == '}') return true
      val key = r.scalar(":", KEY_CAP) ?: return false
      if (r.next() != ':') return false
      val row = newRow(rows, key)
      r.skipSpace()
      if (r.next() != '{') return false
      while (true) {
        r.skipSpace()
        if (r.peek() == '}') {
          r.next()
          break
        }
        val field = r.scalar(":", VALUE_CAP) ?: return false
        if (r.next() != ':') return false
        r.skipSpace()
        val value = r.scalar(",}", VALUE_CAP) ?: return false
        row.set(field, strtoll(value))
        r.skipSpace()
        if (r.peek() == ',') r.next()
      }
      r.skipSpace()
      if (r.peek() == ',') r.next()
    }
  }

  private fun parseBlock(text: String, rows: MutableList<Row>): Boolean {
    var current: Row? = null
    var at = 0
    while (at < text.length) {
      val line = at
      val eol = text.indexOf('\n', at)
      at = if (eol >= 0) eol + 1 else text.length
      var start = line
      while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
      if (start >= text.length || text[start] in "\n\r#") continue
      val r = Reader(text, start)
      val key = r.scalar(":\n", KEY_CAP) ?: return false
      if (r.peek() != ':') return false
      if (start == line) {
        current = newRow(rows, key)
      } else {
        val row = current ?: return false
        r.next()
        r.skipSpace()
        val value = r.scalar("\n", VALUE_CAP) ?: return false
        row.set(key, strtoll(value))
      }
    }
    return true
  }

  /** Reads YAML scalars from [text], as sigil's index reader does. */
  private class Reader(val text: String, var at: Int = 0) {
    fun peek(): Char? = text.getOrNull(at)

    fun next(): Char? = text.getOrNull(at)?.also { at++ }

    fun skipSpace() {
      while (peek()?.let { it in " \t\r\n" } == true) at++
    }

    /**
     * A scalar: single-quoted, double-quoted, or plain up to any of [stops]; null when it runs off
     * the end or takes [cap] characters or more.
     */
    fun scalar(stops: String, cap: Int): String? {
      val out = StringBuilder()
      val quote = peek()
      if (quote == '\'' || quote == '"') {
        at++
        while (true) {
          val c = next() ?: return null
          if (c == quote) {
            if (quote == '\'' && peek() == '\'') {
              at++
              if (out.length + 1 >= cap) return null
              out.append('\'')
              continue
            }
            break
          }
          val char = if (quote == '"' && c == '\\' && peek() != null) next()!! else c
          if (out.length + 1 >= cap) return null
          out.append(char)
        }
        return out.toString()
      }
      while (peek()?.let { it !in stops } == true) {
        if (out.length + 1 >= cap) return null
        out.append(next())
      }
      return out.toString().trimEnd(' ', '\t', '\r')
    }
  }

  /** [value]'s leading integer, as C's strtoll reads it, or 0. */
  private fun strtoll(value: String): Long {
    val match = Regex("^\\s*[+-]?\\d+").find(value) ?: return 0
    return match.value
      .trim()
      .toBigInteger()
      .coerceIn(Long.MIN_VALUE.toBigInteger(), Long.MAX_VALUE.toBigInteger())
      .toLong()
  }

  private const val KEY_CAP = NAME_LENGTH + 8
  private const val VALUE_CAP = 32

  // Card times

  /** The card time at [at] in [entry], read as UTC as PCSX2 reads it, in Unix seconds. */
  private fun todToUnix(entry: ByteArray, at: Int): Long {
    val month = (entry[at + 5].toInt() and 0xFF).takeIf { it != 0 } ?: 1
    val day = (entry[at + 4].toInt() and 0xFF).takeIf { it != 0 } ?: 1
    val date = LocalDate.of(u16(entry, at + 6), 1, 1).plusMonths(month - 1L).plusDays(day - 1L)
    return date.toEpochDay() * 86400 +
      (entry[at + 3].toInt() and 0xFF) * 3600L +
      (entry[at + 2].toInt() and 0xFF) * 60L +
      (entry[at + 1].toInt() and 0xFF)
  }

  private fun unixToTod(t: Long, out: ByteArray, at: Int) {
    val time = LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC)
    out[at] = 0
    out[at + 1] = time.second.toByte()
    out[at + 2] = time.minute.toByte()
    out[at + 3] = time.hour.toByte()
    out[at + 4] = time.dayOfMonth.toByte()
    out[at + 5] = time.monthValue.toByte()
    putU16(out, at + 6, time.year)
  }
}
