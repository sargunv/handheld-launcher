// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from argosy-sigil (https://github.com/rommapp/argosy-sigil, MPL-2.0): src/card_ps2.c and
// the PS2 owner rule in src/card.c, so a game's PS2 saves move between Argosy and the launcher in
// the same per-game card.

package dev.sargunv.handheld.data.saves

import java.io.IOException
import java.security.MessageDigest

/** Bytes that aren't a PS2 memory card, or a save on one, that the launcher can read or write. */
internal class Ps2FormatException(message: String) : IOException(message)

/** A save folder lifted off a PS2 card: its root entry, its "." and ".." entries, and its files. */
internal class Ps2Save(
  val entry: ByteArray,
  val self: ByteArray,
  val parent: ByteArray,
  val files: List<Ps2File>,
) {
  /** The folder's name, such as `BASLUS-20152AC04`. */
  val name: String
    get() = entryName(entry)

  /**
   * The MD5 over the folder's name and each file's name, length and bytes, in directory order.
   * Timestamps, modes and where the save sits on a card leave it alone.
   */
  fun md5(): String {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(entry, NAME, NAME_LENGTH)
    for (file in files) {
      digest.update(file.entry, NAME, NAME_LENGTH)
      digest.update(file.entry, LENGTH, 4)
      digest.update(file.data)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

/** One file of a save folder: its 512-byte directory entry and its bytes. */
internal class Ps2File(val entry: ByteArray, val data: ByteArray)

/** A save folder on a card's root: its [name], and the cluster its directory starts at. */
internal class Ps2Entry(val name: String, val firstCluster: Long) {
  /** The disc serial the folder's name carries, such as `SLUS-20152`, or null. */
  val owner: String?
    get() = ps2Owner(name)
}

/** What a card's root holds: the save folders that read, and the names of those that don't. */
internal class Ps2Listing(val entries: List<Ps2Entry>, val corrupt: List<String>)

/**
 * The disc serial a PS2 save folder's name carries, as `SLUS-20152`: a folder is `B` and a region
 * letter (`A`, `E` or `I`), then the serial, as `BASLUS-20152AC04`. Null for other names.
 */
internal fun ps2Owner(name: String): String? {
  if (name.length < 12 || name[0] != 'B' || name[1] !in "AEI") return null
  val code = name.substring(2, 12)
  if (!code.take(4).all { it in 'A'..'Z' } || !code.drop(5).all { it in '0'..'9' }) return null
  return code.take(4) + "-" + code.drop(5)
}

/**
 * A PS2 memory card in a `.ps2` file, with its pages joined into clusters and the ECC set aside. It
 * reads a card of any size the superblock gives, with or without 16 spare bytes per page, and
 * [format]s an 8 MB card with ECC, as PCSX2 and sigil do.
 *
 * A page's spare bytes are kept as read, since cards carry erased (0xFF) and stale ones; [image]
 * recomputes them only for the pages that changed.
 */
internal class Ps2Card
private constructor(
  private val clusters: ByteArray,
  private val clusterSize: Int,
  private val clustersPerCard: Long,
  private val allocOffset: Long,
  private val allocEnd: Long,
  private val rootdirCluster: Long,
  private val ifc: LongArray,
  /** 16 spare bytes per page as loaded; null without ECC. */
  private val spare: ByteArray?,
  /** One flag per page whose data changed, so its spare is recomputed on write. */
  private val dirty: BooleanArray,
) {
  private val perCluster = clusterSize / 4
  private val entriesPerCluster = clusterSize / ENTRY_SIZE

  // Reading

  private fun inCard(absolute: Long) = absolute in 0 until clustersPerCard

  private fun at(absolute: Long) = (absolute * clusterSize).toInt()

  /** The FAT entry for relative cluster [n], or null when a table on the way is off the card. */
  private fun fatEntry(n: Long): Long? {
    val slot = fatSlot(n) ?: return null
    return u32(clusters, slot)
  }

  /** Where the FAT entry for relative cluster [n] is in [clusters], with its FAT cluster. */
  private fun fatSlot(n: Long): Int? = fatSlotAndCluster(n)?.first

  private fun fatSlotAndCluster(n: Long): Pair<Int, Long>? {
    val fatIndex = n / perCluster
    val ifcSlot = fatIndex / perCluster
    if (ifcSlot >= IFC_SLOTS) return null
    val indirect = ifc[ifcSlot.toInt()]
    if (!inCard(indirect)) return null
    val fatCluster = u32(clusters, at(indirect) + (fatIndex % perCluster).toInt() * 4)
    if (!inCard(fatCluster)) return null
    return at(fatCluster) + (n % perCluster).toInt() * 4 to fatCluster
  }

  /**
   * The chain from relative cluster [first], or null when it meets a free cluster, leaves the card
   * or loops.
   */
  internal fun chain(first: Long): MutableList<Long>? {
    val order = mutableListOf<Long>()
    var cluster = first
    while (order.size < allocEnd) {
      if (cluster !in 0 until allocEnd) return null
      order += cluster
      val value = fatEntry(cluster) ?: return null
      if (value and ALLOCATED == 0L) return null
      val next = value and ALLOCATED.inv() and 0xFFFFFFFFL
      if (next == CHAIN_END) return order
      cluster = next
    }
    return null
  }

  /** [length] bytes of the chain from relative cluster [first], or null when it's shorter. */
  private fun readChain(first: Long, length: Long): ByteArray? {
    val order = chain(first) ?: return null
    if (order.size.toLong() * clusterSize < length) return null
    val out = ByteArray(length.toInt())
    var done = 0
    for (cluster in order) {
      if (done >= length) break
      val absolute = cluster + allocOffset
      if (!inCard(absolute)) return null
      val take = minOf(clusterSize, length.toInt() - done)
      clusters.copyInto(out, done, at(absolute), at(absolute) + take)
      done += take
    }
    return out
  }

  /** The [count] entries of the directory from relative cluster [first], or null. */
  private fun readDir(first: Long, count: Long): ByteArray? {
    if (count == 0L || count > allocEnd * entriesPerCluster) return null
    return readChain(first, count * ENTRY_SIZE)
  }

  private fun rootEntryCount(): Long =
    readChain(rootdirCluster, ENTRY_SIZE.toLong())?.let { u32(it, LENGTH) } ?: 0

  private fun clustersFor(bytes: Long): Long = (bytes + clusterSize - 1) / clusterSize

  /** Whether the folder from [first] and each of its files read, with the clusters they need. */
  private fun folderReads(first: Long, count: Long): Boolean {
    val entries = readDir(first, count) ?: return false
    for (i in 2 until count.toInt()) {
      val e = i * ENTRY_SIZE
      val mode = u16(entries, e + MODE)
      if (mode and MODE_EXISTS == 0 || mode and MODE_FILE == 0) continue
      val need = clustersFor(u32(entries, e + LENGTH))
      if (need > 0 && (chain(u32(entries, e + CLUSTER))?.size ?: 0) < need) return false
    }
    return true
  }

  /**
   * Counts every cluster of the chain from [first] in [claims], or with [check] tells whether
   * another chain also reached any of them. The whole chain counts, past what the entry's length
   * needs, since [delete] frees all of it.
   */
  private fun claimChain(first: Long, claims: IntArray, check: Boolean): Boolean {
    val order = chain(first) ?: return false
    for (cluster in order) {
      if (check && claims[cluster.toInt()] > 1) return true
      if (!check) claims[cluster.toInt()]++
    }
    return false
  }

  /** The clusters of a save folder, its directory's and each file's, as [claimChain] takes them. */
  private fun claimFolder(first: Long, count: Long, claims: IntArray, check: Boolean): Boolean {
    val entries = readDir(first, count) ?: return false
    var shared = claimChain(first, claims, check)
    var i = 2
    while (i < count && !shared) {
      val e = i * ENTRY_SIZE
      val mode = u16(entries, e + MODE)
      if (mode and MODE_EXISTS != 0 && mode and MODE_FILE != 0) {
        if (u32(entries, e + LENGTH) > 0)
          shared = claimChain(u32(entries, e + CLUSTER), claims, check)
      }
      i++
    }
    return shared
  }

  /**
   * The save folders in the card's root. A folder that doesn't read, or shares a cluster with
   * another folder or the root (deleting one would free the other's), is listed as corrupt.
   */
  fun list(): Ps2Listing {
    val count = rootEntryCount()
    val root =
      readDir(rootdirCluster, count) ?: throw Ps2FormatException("The card's root doesn't read")
    val claims = IntArray(allocEnd.toInt())
    claimChain(rootdirCluster, claims, check = false)
    val folders =
      (2 until count.toInt())
        .map { it * ENTRY_SIZE }
        .filter {
          val mode = u16(root, it + MODE)
          mode and MODE_EXISTS != 0 && mode and MODE_DIR != 0
        }
    for (e in folders) claimFolder(u32(root, e + CLUSTER), u32(root, e + LENGTH), claims, false)
    val entries = mutableListOf<Ps2Entry>()
    val corrupt = mutableListOf<String>()
    for (e in folders) {
      val first = u32(root, e + CLUSTER)
      val count = u32(root, e + LENGTH)
      val name = entryName(root, e)
      if (
        !plainName(name) || !folderReads(first, count) || claimFolder(first, count, claims, true)
      ) {
        corrupt += name
      } else {
        entries += Ps2Entry(name, first)
      }
    }
    return Ps2Listing(entries, corrupt)
  }

  /** The root's entry for the folder from [first], with its index, or null. */
  private fun rootEntryFor(first: Long): Pair<ByteArray, Int>? {
    val count = rootEntryCount()
    val root = readDir(rootdirCluster, count) ?: return null
    for (i in 2 until count.toInt()) {
      val e = i * ENTRY_SIZE
      val mode = u16(root, e + MODE)
      if (mode and MODE_EXISTS != 0 && mode and MODE_DIR != 0 && u32(root, e + CLUSTER) == first) {
        return root.copyOfRange(e, e + ENTRY_SIZE) to i
      }
    }
    return null
  }

  /** Lifts the save folder from relative cluster [first] off the card. */
  fun extract(first: Long): Ps2Save {
    val entry = rootEntryFor(first)?.first ?: throw Ps2FormatException("The save isn't on the card")
    val count = u32(entry, LENGTH)
    val entries =
      (if (count >= 2) readDir(first, count) else null)
        ?: throw Ps2FormatException("The save ${entryName(entry)} doesn't read")
    val files = mutableListOf<Ps2File>()
    for (i in 2 until count.toInt()) {
      val e = i * ENTRY_SIZE
      val mode = u16(entries, e + MODE)
      if (mode and MODE_EXISTS == 0) continue
      if (mode and MODE_FILE == 0) {
        throw Ps2FormatException("The save ${entryName(entry)} holds a folder")
      }
      val length = u32(entries, e + LENGTH)
      val data =
        if (length == 0L) ByteArray(0)
        else
          readChain(u32(entries, e + CLUSTER), length)
            ?: throw Ps2FormatException("The save ${entryName(entry)} doesn't read")
      files += Ps2File(entries.copyOfRange(e, e + ENTRY_SIZE), data)
    }
    return Ps2Save(
      entry,
      entries.copyOfRange(0, ENTRY_SIZE),
      entries.copyOfRange(ENTRY_SIZE, 2 * ENTRY_SIZE),
      files,
    )
  }

  // Writing

  private fun markDirty(absolute: Long) {
    val pages = clusterSize / PAGE
    dirty.fill(true, (absolute * pages).toInt(), ((absolute + 1) * pages).toInt())
  }

  internal fun fatSet(n: Long, value: Long) {
    val (slot, fatCluster) =
      fatSlotAndCluster(n) ?: throw Ps2FormatException("The card's FAT doesn't read")
    putU32(clusters, slot, value)
    markDirty(fatCluster)
  }

  private fun isFree(n: Long): Boolean = fatEntry(n)?.let { it and ALLOCATED == 0L } ?: false

  /** The offset of relative cluster [n] in [clusters], marked as changed. */
  private fun relative(n: Long): Int {
    val absolute = n + allocOffset
    markDirty(absolute)
    return at(absolute)
  }

  /** Where entry [index] of the directory whose chain is [chain] is, its cluster marked changed. */
  private fun entrySlot(chain: List<Long>, index: Int): Int =
    relative(chain[index / entriesPerCluster]) + (index % entriesPerCluster) * ENTRY_SIZE

  /** Hands out the card's free clusters lowest first, and links each into its chain. */
  private inner class Allocator {
    private var next = 0L

    fun allocate(after: Long, chained: Boolean): Long {
      while (next < allocEnd && !isFree(next)) next++
      if (next >= allocEnd) throw Ps2NoSpaceException()
      val n = next++
      fatSet(n, 0xFFFFFFFFL)
      if (chained) fatSet(after, ALLOCATED or n)
      return n
    }

    /** Grows [chain] by a cluster when [index] is the first entry of a new one. */
    fun reserveEntry(chain: MutableList<Long>, index: Int) {
      if (index % entriesPerCluster != 0 || index / entriesPerCluster < chain.size) return
      val n = allocate(chain.last(), true)
      val c = relative(n)
      clusters.fill(PAD, c, c + clusterSize)
      chain += n
    }
  }

  private fun clustersNeeded(save: Ps2Save, slot: Long, rootCount: Long): Long {
    var need = if (slot == rootCount && rootCount % entriesPerCluster == 0L) 1L else 0L
    need += clustersFor((save.files.size + 2L) * ENTRY_SIZE)
    for (file in save.files) need += clustersFor(u32(file.entry, LENGTH))
    return need
  }

  private fun freeClusters(): Long = (0 until allocEnd).count { isFree(it) }.toLong()

  /** The first root slot a deleted folder left, or [rootCount] when the new one goes on the end. */
  private fun rootSlotForNew(rootCount: Long): Long {
    val root = readDir(rootdirCluster, rootCount) ?: return rootCount
    for (i in 2 until rootCount.toInt()) {
      if (u16(root, i * ENTRY_SIZE + MODE) and MODE_EXISTS == 0) return i.toLong()
    }
    return rootCount
  }

  private fun nameInRoot(entry: ByteArray, rootCount: Long): Boolean {
    val root = readDir(rootdirCluster, rootCount) ?: return false
    return (2 until rootCount.toInt()).any { i ->
      val e = i * ENTRY_SIZE
      u16(root, e + MODE) and MODE_EXISTS != 0 && sameName(root, e, entry, 0)
    }
  }

  /**
   * Writes [save] into the card's root, taking the lowest free clusters in the order the PS2 does:
   * the root's next cluster when it needs one, the folder's first, then each file's entry cluster
   * when needed and its data. Throws [Ps2NoSpaceException] when the card lacks room, and
   * [Ps2FormatException] when a folder of that name is there, leaving the card as it was.
   */
  fun inject(save: Ps2Save) {
    val rootCount = rootEntryCount()
    if (rootCount < 2) throw Ps2FormatException("The card's root doesn't read")
    if (nameInRoot(save.entry, rootCount)) {
      throw Ps2FormatException("The card already has a save named ${save.name}")
    }
    val slot = rootSlotForNew(rootCount)
    if (clustersNeeded(save, slot, rootCount) > freeClusters()) throw Ps2NoSpaceException()

    val rootChain =
      chain(rootdirCluster) ?: throw Ps2FormatException("The card's root doesn't read")
    val allocator = Allocator()
    if (slot == rootCount) allocator.reserveEntry(rootChain, rootCount.toInt())
    val dirChain = mutableListOf(allocator.allocate(0, false))
    relative(dirChain[0]).let { clusters.fill(PAD, it, it + clusterSize) }

    val rootEntry = entrySlot(rootChain, slot.toInt())
    save.entry.copyInto(clusters, rootEntry)
    putU32(clusters, rootEntry + LENGTH, save.files.size + 2L)
    putU32(clusters, rootEntry + CLUSTER, dirChain[0])
    putU32(clusters, rootEntry + PARENT_INDEX, 0)
    val self = entrySlot(dirChain, 0)
    save.self.copyInto(clusters, self)
    putU32(clusters, self + PARENT_INDEX, slot)
    save.parent.copyInto(clusters, entrySlot(dirChain, 1))

    for ((i, file) in save.files.withIndex()) {
      val index = i + 2
      allocator.reserveEntry(dirChain, index)
      val length = u32(file.entry, LENGTH)
      var first = 0L
      var prev = 0L
      for (k in 0 until clustersFor(length)) {
        val n = allocator.allocate(prev, k > 0)
        if (k == 0L) first = n
        val c = relative(n)
        val from = (k * clusterSize).toInt()
        val take = minOf(clusterSize.toLong(), length - from).toInt()
        clusters.fill(PAD, c, c + clusterSize)
        file.data.copyInto(clusters, c, from, from + take)
        prev = n
      }
      val e = entrySlot(dirChain, index)
      file.entry.copyInto(clusters, e)
      putU32(clusters, e + CLUSTER, if (length > 0) first else 0)
    }

    val rootSelf = entrySlot(rootChain, 0)
    if (slot == rootCount) putU32(clusters, rootSelf + LENGTH, rootCount + 1)
    if (todLater(save.self, CREATED, clusters, rootSelf + MODIFIED)) {
      save.self.copyInto(clusters, rootSelf + MODIFIED, CREATED, CREATED + TOD_SIZE)
    }
  }

  /** Deletes the save folder from relative cluster [first], freeing its clusters. */
  fun delete(first: Long) {
    val index =
      rootEntryFor(first)?.second ?: throw Ps2FormatException("The save isn't on the card")
    val save = extract(first)
    for (i in 0..save.files.size) {
      val start = if (i < save.files.size) u32(save.files[i].entry, CLUSTER) else first
      val hasData = i == save.files.size || u32(save.files[i].entry, LENGTH) > 0
      if (hasData) chain(start)?.forEach { fatSet(it, CHAIN_END) }
    }
    val rootChain = chain(rootdirCluster) ?: return
    if (index / entriesPerCluster < rootChain.size) {
      val e = entrySlot(rootChain, index)
      putU16(clusters, e + MODE, u16(clusters, e + MODE) and MODE_EXISTS.inv())
    }
  }

  /** Whether the card holds a folder named as [save] whose entries and files equal it. */
  fun holds(save: Ps2Save): Boolean {
    val count = rootEntryCount()
    val root = readDir(rootdirCluster, count) ?: return false
    for (i in 2 until count.toInt()) {
      val e = i * ENTRY_SIZE
      if (u16(root, e + MODE) and MODE_EXISTS == 0 || !sameName(root, e, save.entry, 0)) continue
      if (!sameEntry(root.copyOfRange(e, e + ENTRY_SIZE), save.entry)) continue
      val found =
        try {
          extract(u32(root, e + CLUSTER))
        } catch (ex: Ps2FormatException) {
          continue
        }
      if (
        found.files.size == save.files.size &&
          found.files.zip(save.files).all { (a, b) ->
            sameEntry(a.entry, b.entry) && a.data.contentEquals(b.data)
          }
      ) {
        return true
      }
    }
    return false
  }

  /** The card as a `.ps2` file. */
  fun image(): ByteArray {
    val pages = (clustersPerCard * (clusterSize / PAGE)).toInt()
    val stride = PAGE + if (spare != null) SPARE else 0
    val out = ByteArray(pages * stride)
    for (p in 0 until pages) {
      clusters.copyInto(out, p * stride, p * PAGE, (p + 1) * PAGE)
      if (spare == null) continue
      val dst = p * stride + PAGE
      if (!dirty[p]) {
        spare.copyInto(out, dst, p * SPARE, (p + 1) * SPARE)
      } else {
        for (chunk in 0 until 4) ecc(clusters, p * PAGE + chunk * 128, out, dst + chunk * 3)
      }
    }
    return out
  }

  companion object {
    /**
     * Reads a `.ps2` file card. Throws [Ps2FormatException] when [bytes] isn't one: no superblock,
     * a layout off the card, or a size that fits neither 512- nor 528-byte pages.
     */
    fun load(bytes: ByteArray): Ps2Card {
      if (bytes.size < PAGE || bytes.copyOf(MAGIC.length).decodeToString() != MAGIC) {
        throw Ps2FormatException("It isn't a PS2 memory card")
      }
      val pageLength = u16(bytes, 0x28)
      val pagesPerCluster = u16(bytes, 0x2A)
      val count = u32(bytes, 0x30)
      if (pageLength != PAGE || pagesPerCluster !in 1..16 || count !in 1..MAX_CLUSTERS) {
        throw Ps2FormatException("The card's superblock doesn't read")
      }
      val allocOffset = u32(bytes, 0x34)
      val allocEnd = u32(bytes, 0x38)
      if (allocOffset >= count || allocEnd == 0L || allocEnd > count - allocOffset) {
        throw Ps2FormatException("The card's superblock doesn't read")
      }
      val pages = count * pagesPerCluster
      val ecc =
        when (bytes.size.toLong()) {
          pages * (PAGE + SPARE) -> true
          pages * PAGE -> false
          else -> throw Ps2FormatException("The card's size doesn't match its superblock")
        }
      val stride = PAGE + if (ecc) SPARE else 0
      val clusters = ByteArray((pages * PAGE).toInt())
      val spare = if (ecc) ByteArray((pages * SPARE).toInt()) else null
      for (p in 0 until pages.toInt()) {
        bytes.copyInto(clusters, p * PAGE, p * stride, p * stride + PAGE)
        spare?.let { bytes.copyInto(it, p * SPARE, p * stride + PAGE, (p + 1) * stride) }
      }
      return Ps2Card(
        clusters,
        PAGE * pagesPerCluster,
        count,
        allocOffset,
        allocEnd,
        u32(bytes, 0x3C),
        LongArray(IFC_SLOTS) { u32(bytes, 0x50 + it * 4) },
        spare,
        BooleanArray(pages.toInt()),
      )
    }

    /**
     * An empty formatted 8 MB card with ECC, laid out as PCSX2 and mymc lay one out, its root
     * stamped 2000-01-01 00:00:00 as sigil stamps the cards it builds.
     */
    fun format(): Ps2Card {
      val clusterSize = PAGE * FORMAT_PAGES_PER_CLUSTER
      val pages = FORMAT_CLUSTERS * FORMAT_PAGES_PER_CLUSTER
      val clusters = ByteArray(FORMAT_CLUSTERS * clusterSize)
      val spare = ByteArray(pages * SPARE)
      val dirty = BooleanArray(pages) { true }
      writeSuperblock(clusters, 0)
      val perCluster = clusterSize / 4
      val fatClusters = FORMAT_ALLOC_OFFSET - FORMAT_FAT_FIRST
      val indirect = FORMAT_IFC * clusterSize
      clusters.fill(0xFF.toByte(), indirect, indirect + clusterSize)
      for (i in 0 until fatClusters) putU32(
        clusters,
        indirect + i * 4,
        FORMAT_FAT_FIRST + i.toLong(),
      )
      for (n in 0 until fatClusters * perCluster) {
        val value = if (n == 0 || n >= FORMAT_ALLOC_END) 0xFFFFFFFFL else CHAIN_END
        putU32(
          clusters,
          (FORMAT_FAT_FIRST + n / perCluster) * clusterSize + (n % perCluster) * 4,
          value,
        )
      }
      val root = FORMAT_ALLOC_OFFSET * clusterSize
      writeDirEntry(clusters, root, MODE_ROOT_SELF, 2, BUILT_TOD, ".")
      writeDirEntry(clusters, root + ENTRY_SIZE, MODE_ROOT_PARENT, 0, BUILT_TOD, "..")
      // The second backup block stays erased.
      val clustersPerBlock = FORMAT_PAGES_PER_BLOCK / FORMAT_PAGES_PER_CLUSTER
      val erased = FORMAT_BACKUP2 * clustersPerBlock * clusterSize
      clusters.fill(0xFF.toByte(), erased, erased + clustersPerBlock * clusterSize)
      val erasedPage = FORMAT_BACKUP2 * FORMAT_PAGES_PER_BLOCK
      dirty.fill(false, erasedPage, erasedPage + FORMAT_PAGES_PER_BLOCK)
      spare.fill(0xFF.toByte(), erasedPage * SPARE, (erasedPage + FORMAT_PAGES_PER_BLOCK) * SPARE)
      return Ps2Card(
        clusters,
        clusterSize,
        FORMAT_CLUSTERS.toLong(),
        FORMAT_ALLOC_OFFSET.toLong(),
        FORMAT_ALLOC_END.toLong(),
        0,
        LongArray(IFC_SLOTS).also { it[0] = FORMAT_IFC.toLong() },
        spare,
        dirty,
      )
    }

    /** The superblock page of an empty 8 MB card, written over zeroed bytes at [at]. */
    internal fun writeSuperblock(out: ByteArray, at: Int) {
      MAGIC.encodeToByteArray().copyInto(out, at)
      "1.2.0.0".encodeToByteArray().copyInto(out, at + MAGIC.length)
      putU16(out, at + 0x28, PAGE)
      putU16(out, at + 0x2A, FORMAT_PAGES_PER_CLUSTER)
      putU16(out, at + 0x2C, FORMAT_PAGES_PER_BLOCK)
      putU16(out, at + 0x2E, 0xFF00)
      putU32(out, at + 0x30, FORMAT_CLUSTERS.toLong())
      putU32(out, at + 0x34, FORMAT_ALLOC_OFFSET.toLong())
      putU32(out, at + 0x38, FORMAT_ALLOC_END.toLong())
      putU32(out, at + 0x3C, 0)
      putU32(out, at + 0x40, FORMAT_BACKUP1.toLong())
      putU32(out, at + 0x44, FORMAT_BACKUP2.toLong())
      putU32(out, at + 0x50, FORMAT_IFC.toLong())
      out.fill(0xFF.toByte(), at + 0xD0, at + 0x150)
      out[at + 0x150] = CARD_TYPE
      out[at + 0x151] = CARD_FLAGS
    }

    private fun writeDirEntry(
      out: ByteArray,
      at: Int,
      mode: Int,
      length: Long,
      tod: ByteArray,
      name: String,
    ) {
      out.fill(0, at, at + ENTRY_SIZE)
      putU16(out, at + MODE, mode)
      putU32(out, at + LENGTH, length)
      tod.copyInto(out, at + CREATED)
      tod.copyInto(out, at + MODIFIED)
      name.encodeToByteArray().copyInto(out, at + NAME)
    }

    /** The 3-byte ECC a PS2 card keeps for the 128-byte chunk at [from], written to [to]. */
    internal fun ecc(chunk: ByteArray, from: Int, out: ByteArray, to: Int) {
      var column = 0
      var lineEven = 0
      var lineOdd = 0
      for (i in 0 until 128) {
        val c = ECC_COLUMNS[chunk[from + i].toInt() and 0xFF]
        column = column xor c
        if (c and 0x80 != 0) {
          lineEven = lineEven xor (i.inv() and 0xFF)
          lineOdd = lineOdd xor i
        }
      }
      out[to] = (column.inv() and 0x77).toByte()
      out[to + 1] = (lineEven.inv() and 0x7F).toByte()
      out[to + 2] = (lineOdd.inv() and 0x7F).toByte()
    }

    // Each byte's column parities, as the PS2 ECC table has them: bits 0-2 and 4-6 the even and
    // odd halves of each bit pairing, bit 7 the byte's own parity.
    private val ECC_COLUMNS =
      IntArray(256) { b ->
        val groups = intArrayOf(0x55, 0x33, 0x0F, 0xAA, 0xCC, 0xF0)
        val positions = intArrayOf(0, 1, 2, 4, 5, 6)
        var out = 0
        for (i in 0 until 6) if (Integer.bitCount(b and groups[i]) % 2 == 1)
          out = out or (1 shl positions[i])
        if (Integer.bitCount(b) % 2 == 1) out = out or 0x80
        out
      }
  }
}

/** The card lacks the room a save takes. */
internal class Ps2NoSpaceException : IOException("The memory card is full")

// The card's layout.

internal const val ENTRY_SIZE = 512
internal const val MODE = 0x00
internal const val LENGTH = 0x04
internal const val CREATED = 0x08
internal const val CLUSTER = 0x10
internal const val PARENT_INDEX = 0x14
internal const val MODIFIED = 0x18
internal const val NAME = 0x40
internal const val NAME_LENGTH = 32
internal const val TOD_SIZE = 8

internal const val MODE_EXISTS = 0x8000
internal const val MODE_DIR = 0x0020
internal const val MODE_FILE = 0x0010

private const val MAGIC = "Sony PS2 Memory Card Format "
private const val PAGE = 512
private const val SPARE = 16
private const val MAX_CLUSTERS = 1L shl 20
private const val IFC_SLOTS = 32
internal const val ALLOCATED = 0x80000000L
private const val CHAIN_END = 0x7FFFFFFFL
private const val PAD = 0xFF.toByte()

private const val FORMAT_CLUSTERS = 8192
private const val FORMAT_PAGES_PER_CLUSTER = 2
private const val FORMAT_PAGES_PER_BLOCK = 16
private const val FORMAT_IFC = 8
private const val FORMAT_FAT_FIRST = 9
private const val FORMAT_ALLOC_OFFSET = 41
private const val FORMAT_ALLOC_END = 8135
private const val FORMAT_BACKUP1 = 1023
private const val FORMAT_BACKUP2 = 1022
private const val CARD_TYPE: Byte = 2
private const val CARD_FLAGS: Byte = 0x2B
private const val MODE_ROOT_SELF = 0x8427
private const val MODE_ROOT_PARENT = 0xA426

/** The root time of a card sigil builds: 2000-01-01 00:00:00. */
private val BUILT_TOD = byteArrayOf(0, 0, 0, 0, 1, 1, 0xD0.toByte(), 0x07)

internal fun u16(bytes: ByteArray, at: Int): Int =
  (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

internal fun u32(bytes: ByteArray, at: Int): Long =
  (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }

internal fun putU16(bytes: ByteArray, at: Int, value: Int) {
  bytes[at] = value.toByte()
  bytes[at + 1] = (value shr 8).toByte()
}

internal fun putU32(bytes: ByteArray, at: Int, value: Long) {
  for (i in 0 until 4) bytes[at + i] = (value shr (8 * i)).toByte()
}

/** The name in the entry at [at]: up to 32 bytes, ending at the first NUL. */
internal fun entryName(entry: ByteArray, at: Int = 0): String {
  val field = entry.copyOfRange(at + NAME, at + NAME + NAME_LENGTH)
  val end = field.indexOf(0).let { if (it < 0) NAME_LENGTH else it }
  return String(field, 0, end, Charsets.ISO_8859_1)
}

private fun sameName(a: ByteArray, atA: Int, b: ByteArray, atB: Int): Boolean =
  (0 until NAME_LENGTH).all { a[atA + NAME + it] == b[atB + NAME + it] }

/** Whether two entries are equal but for where their data sits. */
private fun sameEntry(a: ByteArray, b: ByteArray): Boolean {
  val x = a.copyOf().also { putU32(it, CLUSTER, 0) }
  val y = b.copyOf().also { putU32(it, CLUSTER, 0) }
  return x.contentEquals(y)
}

/** Whether the card time at [atA] in [a] is later than the one at [atB] in [b]. */
private fun todLater(a: ByteArray, atA: Int, b: ByteArray, atB: Int): Boolean {
  for (i in intArrayOf(7, 6, 5, 4, 3, 2, 1)) {
    val x = a[atA + i].toInt() and 0xFF
    val y = b[atB + i].toInt() and 0xFF
    if (x != y) return x > y
  }
  return false
}

/** A save folder or file name that stays one path segment on any host. */
internal fun plainName(name: String): Boolean =
  name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
