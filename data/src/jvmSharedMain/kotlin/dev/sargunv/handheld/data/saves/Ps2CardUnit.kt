// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// How a game's saves are collected from shared PS2 cards and put back follows argosy-sigil
// (https://github.com/rommapp/argosy-sigil, MPL-2.0): src/sync_cards.c, src/sync_folder_cards.c and
// the card units in src/sync_units.c, so Argosy and the launcher sync the same per-game card.

package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.writeAtomically
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * A PS2 game's saves on an emulator's shared memory cards: the save folders whose name carries
 * [serial] (such as `BASLUS-20152AC04` for `SLUS-20152`) on the slot cards in the first of [roots],
 * the emulator's `memcards` folders, that exists. Each card is a file card or a PCSX2 folder card.
 *
 * They sync as one per-game 8 MB card with ECC named [name], holding only those folders: the unit
 * Argosy syncs through sigil. A pull puts the card's folders back on the card each was on, a new
 * one on slot 1's, and leaves every other game's saves alone. [folderCards] makes a slot 1 card the
 * launcher has to create a folder card, for an app that reads only those.
 */
data class Ps2CardRoots(
  val roots: List<File>,
  val serial: String,
  val name: String,
  val folderCards: Boolean = false,
)

/** [Ps2CardRoots]'s saves, as [SaveSync] reads and writes them. */
internal class Ps2CardUnit(private val save: Ps2CardRoots) : SaveUnit {
  /**
   * A card on the device as the launcher read it: a file card, or a folder card's saves of this
   * game on a scratch card. [bytes] is a file card's file as read.
   */
  private class Card(val file: File, val folder: Boolean, val card: Ps2Card, val bytes: ByteArray?)

  /** The device's cards in [root], and the one a new save goes on. */
  private class Cards(val cards: MutableList<Card>, val primary: File)

  /** The slot cards in [root], in either case, as the file or folder that's there or would be. */
  private fun slots(root: File): List<File> {
    val present = root.listFiles().orEmpty()
    return SLOTS.map { name ->
      present.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: File(root, name)
    }
  }

  /**
   * The cards in [root] that hold anything: file cards first, then folder cards. An empty card
   * file, or one with every byte 0xFF (the erased card PCSX2's forks make for a slot never used),
   * holds nothing. A file that isn't a card fails, so its saves are never taken as none.
   */
  private fun gather(root: File): Cards {
    val slots = slots(root)
    val cards = mutableListOf<Card>()
    for (file in slots.filter { it.isFile }) {
      val bytes = file.readBytes()
      if (holdsNothing(bytes)) continue
      val card =
        try {
          Ps2Card.load(bytes)
        } catch (e: Ps2FormatException) {
          throw IOException("${file.path} isn't a memory card the launcher can read: ${e.message}")
        }
      cards += Card(file, folder = false, card, bytes)
    }
    for (dir in slots.filter { it.isDirectory }) cards +=
      Card(dir, folder = true, folderCard(dir), null)
    return Cards(cards, slots.firstOrNull { it.exists() } ?: slots.first())
  }

  /** This game's save folders on the folder card [dir], by name, packed onto a scratch card. */
  private fun folderCard(dir: File): Ps2Card {
    val card = Ps2Card.format()
    for (folder in owned(dir)) {
      val files = folderFiles(folder)
      if (files.isEmpty()) continue
      val packed =
        try {
          Ps2FolderCard.pack(folder.name, files)
        } catch (e: Ps2FormatException) {
          throw IOException("The save folder ${folder.path} is damaged: ${e.message}")
        }
      card.inject(packed)
    }
    return card
  }

  /** The save folders on the folder card [dir] that are this game's, in name order. */
  private fun owned(dir: File): List<File> =
    dir
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && ps2Owner(it.name) == save.serial }
      .sortedWith(compareBy(CODE_POINTS) { it.name })

  /** The files of a folder card's save folder, by path inside it, in name order. */
  private fun folderFiles(folder: File): List<Pair<String, ByteArray>> =
    folder
      .walkTopDown()
      .filter { it.isFile }
      .map { it.relativeTo(folder).invariantSeparatorsPath to it }
      .sortedWith(compareBy(CODE_POINTS) { it.first })
      .map { (path, file) -> path to file.readBytes() }
      .toList()

  /**
   * This game's saves on [cards], each with the card it's on: the first copy of a name, as sigil
   * takes it. A card holding a damaged save of the game fails, since reading on would take that
   * save as deleted.
   */
  private fun gameSaves(cards: List<Card>): List<Pair<Ps2Save, Card>> {
    val saves = mutableListOf<Pair<Ps2Save, Card>>()
    for (card in cards) {
      val listing = listing(card)
      if (listing.corrupt.any { ps2Owner(it) == save.serial }) {
        throw IOException("A save of this game on ${card.file.path} is damaged")
      }
      for (entry in listing.entries) {
        if (entry.owner != save.serial || saves.any { it.first.name == entry.name }) continue
        val lifted =
          try {
            card.card.extract(entry.firstCluster)
          } catch (e: Ps2FormatException) {
            throw IOException("A save of this game on ${card.file.path} is damaged: ${e.message}")
          }
        saves += lifted to card
      }
    }
    return saves
  }

  private fun listing(card: Card): Ps2Listing =
    try {
      card.card.list()
    } catch (e: Ps2FormatException) {
      throw IOException("${card.file.path} isn't a memory card the launcher can read: ${e.message}")
    }

  /** The per-game card holding [saves], as sigil builds it. */
  private fun unitOf(saves: List<Ps2Save>): ByteArray {
    val unit = Ps2Card.format()
    for (s in saves) unit.inject(s)
    return unit.image()
  }

  private fun collect(cards: List<Card>): LocalCopy? {
    val saves = gameSaves(cards)
    if (saves.isEmpty()) return null
    val bytes = unitOf(saves.map { it.first })
    val holding = saves.map { it.second }.distinct()
    val modified = holding.maxOf { card ->
      if (!card.folder) card.file.lastModified()
      else
        owned(card.file).flatMap { it.walkTopDown().toList() }.maxOfOrNull { it.lastModified() }
          ?: 0
    }
    return LocalCopy(
      holding.first().file,
      save.name,
      bytes,
      md5(bytes),
      Instant.fromEpochMilliseconds(modified),
    )
  }

  private fun root(): File? = save.roots.firstOrNull { it.isDirectory }

  override fun read(): LocalCopy? = root()?.let { collect(gather(it).cards) }

  // The emulator's memcards folder isn't created, since the emulator has to own it.
  override fun target(serverName: String): File? {
    val root = save.roots.firstOrNull { it.isDirectory && it.canWrite() } ?: return null
    return gather(root).primary
  }

  override fun folders() = save.roots.map { it.path }

  override fun write(target: File, bytes: ByteArray, replaced: String?, keep: (LocalCopy) -> Unit) {
    val incoming = unitSaves(bytes)
    val root = target.parentFile ?: throw IOException("${target.path} has no folder")
    val gathered = gather(root)
    val cards = gathered.cards
    // What the cards hold of this game now is in the history already, unless the emulator wrote
    // since.
    collect(cards)?.let { if (it.hash != replaced) keep(it) }

    // Take the game's saves off every card, noting where each was, then put the server's back on
    // the card each was on, a new one on slot 1's.
    val placement = mutableMapOf<String, Card>()
    val changed = linkedSetOf<Card>()
    for (card in cards) {
      for (entry in listing(card).entries.filter { it.owner == save.serial }) {
        card.card.delete(entry.firstCluster)
        placement.putIfAbsent(entry.name, card)
        changed += card
      }
    }
    val primary =
      cards.firstOrNull { it.file == gathered.primary }
        ?: Card(
            gathered.primary,
            folder = save.folderCards && !gathered.primary.isFile,
            Ps2Card.format(),
            bytes = null,
          )
          .also { cards += it }
    val placed = mutableMapOf<Card, MutableList<Ps2Save>>()
    for (s in incoming) {
      val card = placement[s.name] ?: primary
      try {
        card.card.inject(s)
      } catch (e: Ps2NoSpaceException) {
        throw IOException("${card.file.path} has no room for the save ${s.name}")
      }
      placed.getOrPut(card) { mutableListOf() } += s
      changed += card
    }

    // Everything is checked before anything is written, and each file card again just before.
    val writes = changed.map { card ->
      if (card.folder) folderWrite(card) else fileWrite(card, placed[card].orEmpty())
    }
    for (card in changed.filter { !it.folder }) checkUnchanged(card)
    for (write in writes) write()
  }

  /** Fails when the file card [card] isn't what was read, so a save written since isn't lost. */
  private fun checkUnchanged(card: Card) {
    val now = if (card.file.isFile) card.file.readBytes() else null
    val unchanged =
      card.bytes?.let { now?.contentEquals(it) == true } ?: (now == null || holdsNothing(now))
    if (!unchanged) throw IOException("${card.file.path} changed while the launcher was writing it")
  }

  /**
   * The file card [card]'s new image, checked to hold [placed], as a write that first checks the
   * file is still what was read.
   */
  private fun fileWrite(card: Card, placed: List<Ps2Save>): () -> Unit {
    val image = card.card.image()
    val back = Ps2Card.load(image)
    if (!placed.all { back.holds(it) }) {
      throw IOException("The saves didn't read back from the new ${card.file.name}")
    }
    return {
      checkUnchanged(card)
      writeAtomically(Path(card.file.path), image)
      if (!card.file.readBytes().contentEquals(image)) {
        throw IOException("${card.file.path} didn't read back as written")
      }
    }
  }

  /**
   * The folder card [card]'s save folders of this game, unpacked and checked, as a write that swaps
   * them in for the ones there, beside a `_pcsx2_superblock` PCSX2 reads.
   */
  private fun folderWrite(card: Card): () -> Unit {
    val folders =
      listing(card)
        .entries
        .filter { it.owner == save.serial }
        .map { entry ->
          val lifted = card.card.extract(entry.firstCluster)
          val files = Ps2FolderCard.unpack(lifted)
          if (Ps2FolderCard.pack(lifted.name, files).md5() != lifted.md5()) {
            throw IOException("The save ${lifted.name} doesn't keep as a folder")
          }
          lifted.name to files
        }
    return {
      val dir = card.file
      dir.mkdirs()
      val superblock = File(dir, Ps2FolderCard.SUPERBLOCK)
      if (!Ps2FolderCard.superblockUsable(superblock.takeIf { it.isFile }?.readBytes())) {
        superblock.writeBytes(Ps2FolderCard.superblock())
      }
      swapFolders(dir, folders)
    }
  }

  /**
   * Puts [folders] in [dir] in place of this game's save folders there. They're written beside the
   * card first, so the emulator never sees a folder half written, and the old ones are put back if
   * a move fails.
   */
  private fun swapFolders(dir: File, folders: List<Pair<String, List<Pair<String, ByteArray>>>>) {
    val stamp = System.currentTimeMillis()
    val staging = File(dir.parentFile, "$RESERVED$stamp-new")
    val aside = File(dir.parentFile, "$RESERVED$stamp-old")
    try {
      for ((name, files) in folders) {
        val folder = File(staging, name).apply { mkdirs() }
        for ((path, data) in files) {
          val file = File(folder, path)
          file.parentFile.mkdirs()
          file.writeBytes(data)
        }
      }
      swapIn(listOf(Swap(dir, owned(dir), staging, aside)))
      aside.deleteRecursively()
    } finally {
      staging.deleteRecursively()
    }
  }

  /**
   * This game's saves in a unit from the server: sigil's per-game card, maybe ending in Argosy's
   * hardcore marker, or a zip of PCSX2 save folders (`<folder>/<file>`, or under `<card>.ps2/`) or
   * of cards, as clients uploaded before they used sigil. Saves of other games are left out.
   */
  private fun unitSaves(bytes: ByteArray): List<Ps2Save> {
    val unit = withoutMarker(bytes)
    val cards =
      try {
        if (isZipBytes(unit)) zipCards(unit) else listOf(Ps2Card.load(unit))
      } catch (e: Ps2FormatException) {
        throw IOException("The save from RomM isn't a PS2 memory card: ${e.message}")
      }
    val saves = mutableListOf<Ps2Save>()
    for (card in cards) {
      val listing = card.list()
      if (listing.corrupt.isNotEmpty()) {
        throw IOException(
          "The save from RomM holds a damaged save: ${listing.corrupt.joinToString()}",
        )
      }
      for (entry in listing.entries) {
        if (entry.owner != save.serial || saves.any { it.name == entry.name }) continue
        saves += card.extract(entry.firstCluster)
      }
    }
    if (saves.isEmpty()) throw IOException("The save from RomM holds none of this game's saves")
    return saves
  }

  private fun zipCards(bytes: ByteArray): List<Ps2Card> {
    val members = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) members += entry.name to zip.readBytes()
      }
    }
    val cards = members.filter { '/' !in it.first }.map { Ps2Card.load(it.second) }.toMutableList()
    // Folder members, by save folder, in the order the zip has them.
    val folders = linkedMapOf<String, MutableList<Pair<String, ByteArray>>>()
    for ((name, data) in members) {
      var path = name
      val first = path.substringBefore('/', "")
      if (first.length > 4 && first.endsWith(".ps2", ignoreCase = true))
        path = path.substringAfter('/')
      val folder = path.substringBefore('/', "")
      val inFolder = path.substringAfter('/', "")
      if (folder.isEmpty() || inFolder.isEmpty()) continue
      if ('/' in inFolder && !inFolder.startsWith("_pcsx2_meta/")) continue
      folders.getOrPut(folder) { mutableListOf() } += inFolder to data
    }
    if (folders.isNotEmpty()) {
      val card = Ps2Card.format()
      for ((folder, files) in folders) card.inject(Ps2FolderCard.pack(folder, files))
      cards += card
    }
    return cards
  }

  private companion object {
    /** The cards PCSX2 and its forks use unless their settings name others: slot 1's, then 2's. */
    val SLOTS = listOf("Mcd001.ps2", "Mcd002.ps2")
  }
}

/**
 * [bytes] without the hardcore marker Argosy appends to a unit: `{"h":true,...}`, its length,
 * `ARGOSY` 01 00.
 */
private fun withoutMarker(bytes: ByteArray): ByteArray {
  val tail = "ARGOSY".encodeToByteArray() + byteArrayOf(1, 0)
  if (bytes.size < tail.size + 4) return bytes
  val end = bytes.size - tail.size
  if (!bytes.copyOfRange(end, bytes.size).contentEquals(tail)) return bytes
  val length = u32(bytes, end - 4)
  val start = end - 4 - length
  return if (start >= 0) bytes.copyOf(start.toInt()) else bytes
}

/** An empty card file, or one with every byte 0xFF: the erased card PCSX2's forks make. */
private fun holdsNothing(bytes: ByteArray) = bytes.all { it == 0xFF.toByte() }

private fun isZipBytes(bytes: ByteArray) =
  bytes.size >= 4 &&
    bytes[0] == 'P'.code.toByte() &&
    bytes[1] == 'K'.code.toByte() &&
    bytes[2].toInt() == 3 &&
    bytes[3].toInt() == 4
