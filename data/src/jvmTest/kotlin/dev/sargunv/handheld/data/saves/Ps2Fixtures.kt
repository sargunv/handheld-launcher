package dev.sargunv.handheld.data.saves

import java.io.File
import java.util.zip.GZIPInputStream

/** Ace Combat 04's serial: two save folders on the cards below, with PCSX2 indexes. */
internal const val ACE_COMBAT = "SLUS-20152"

/** Jak and Daxter's serial: one save folder, as PocketGOAL writes it, without an index. */
internal const val JAK = "SCUS-97124"

private fun pattern(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

/**
 * A PCSX2 folder card's files, by path inside it: Ace Combat's two save folders, one with a block
 * YAML index and one with a flow one, Jak's without one, another game's, and the system folder. The
 * sigil units in the test resources were collected from exactly this card.
 */
internal val folderCardFiles: Map<String, ByteArray> =
  linkedMapOf(
    "BASLUS-20152AC04/icon.sys" to pattern(964, 1),
    "BASLUS-20152AC04/AC04.DAT" to pattern(5000, 2),
    "BASLUS-20152AC04/_pcsx2_index" to
      """
      |${'$'}ROOT:
      |  timeCreated: 1100000000
      |  timeModified: 1100000100
      |AC04.DAT:
      |  order: 2
      |  timeCreated: 1100000050
      |  timeModified: 1100000090
      |icon.sys:
      |  order: 1
      |  timeCreated: 1100000010
      |  timeModified: 1100000020
      |"""
        .trimMargin()
        .encodeToByteArray(),
    "BASLUS-20152SYS/SYS.DAT" to pattern(100, 3),
    "BASLUS-20152SYS/_pcsx2_index" to
      ("{\$ROOT: {timeCreated: 1200000000, timeModified: 1200000000}, " +
          "'SYS.DAT': {order: 1, timeCreated: 1200000000, timeModified: 1200000001}}")
        .encodeToByteArray(),
    "BASCUS-97124AYBABTU!/icon.sys" to pattern(964, 4),
    "BASCUS-97124AYBABTU!/BASCUS-97124AYBABTU!" to pattern(45, 5),
    "BASCUS-97124AYBABTU!/bank0" to pattern(20000, 6),
    "BASCUS-97124AYBABTU!/bank1" to ByteArray(20000),
    "BESLES-12345OTHER/OTHER.DAT" to pattern(700, 7),
    "BEDATA-SYSTEM/icon.sys" to pattern(100, 8),
  )

/** The save folder [folder] of [folderCardFiles], packed as PCSX2 puts it on a card. */
internal fun packed(folder: String): Ps2Save =
  Ps2FolderCard.pack(
    folder,
    folderCardFiles
      .filterKeys { it.startsWith("$folder/") }
      .map { (path, data) -> path.removePrefix("$folder/") to data },
  )

/** Writes a file card holding [saves] to [file]. */
internal fun fileCard(file: File, vararg saves: Ps2Save) {
  val card = Ps2Card.format()
  for (save in saves) card.inject(save)
  file.writeBytes(card.image())
}

/** Writes [files] into the folder [dir], by path inside it. */
internal fun writeTree(dir: File, files: Map<String, ByteArray>) {
  for ((path, data) in files) File(dir, path).apply { parentFile.mkdirs() }.writeBytes(data)
}

/** The files under [dir], by path inside it. */
internal fun readTree(dir: File): Map<String, ByteArray> =
  dir
    .walkTopDown()
    .filter { it.isFile }
    .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

/**
 * The unit sigil collected for [serial] from [folderCardFiles], as a PCSX2 folder card in slot 1.
 */
internal fun sigilUnit(serial: String): ByteArray =
  GZIPInputStream(
      checkNotNull(Ps2Fixtures::class.java.getResourceAsStream("/ps2/sigil-$serial.ps2.gz")),
    )
    .use { it.readBytes() }

private object Ps2Fixtures
