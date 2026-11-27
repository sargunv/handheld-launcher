package dev.sargunv.handheld.data.saves

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A game's PS2 saves on shared cards, checked against units argosy-sigil collected from the same
 * card ([folderCardFiles]), so the launcher and Argosy sync the same bytes.
 */
class Ps2CardTest {
  private val root = Files.createTempDirectory("ps2-card-test").toFile()

  @AfterTest fun deleteRoot() = check(root.deleteRecursively())

  /** An emulator's empty `memcards` folder. */
  private fun memcards(device: String) = File(root, "$device/memcards").apply { mkdirs() }

  private fun unit(memcards: File, serial: String, folderCards: Boolean = false) =
    Ps2CardUnit(listOf(memcards), serial, "Game (USA).ps2", folderCards)

  private fun save(folder: String, vararg files: Pair<String, String>) =
    Ps2FolderCard.pack(folder, files.map { it.first to it.second.encodeToByteArray() })

  private fun names(file: File) =
    Ps2Card.load(file.readBytes()).list().entries.map { it.name }.toSet()

  private fun assertSameBytes(expected: ByteArray, actual: ByteArray?, message: String) =
    assertTrue(actual != null && expected.contentEquals(actual), message)

  @Test
  fun collectsTheUnitSigilCollects() {
    val cards = memcards("folder")
    writeTree(File(cards, "Mcd001.ps2"), folderCardFiles)
    for (serial in listOf(ACE_COMBAT, JAK)) {
      val local = assertNotNull(unit(cards, serial).read())
      assertSameBytes(sigilUnit(serial), local.bytes, "$serial's unit is sigil's, byte for byte")
      assertEquals(md5(local.bytes), local.hash)
      assertEquals("Game (USA).ps2", local.name)
      assertEquals(File(cards, "Mcd001.ps2"), local.file)
    }
    assertNull(unit(cards, "SLUS-99999").read(), "A game with no saves on the cards has none")
  }

  @Test
  fun aFileCardGivesTheSameUnitAsAFolderCard() {
    val cards = memcards("file")
    fileCard(
      File(cards, "Mcd001.ps2"),
      packed("BESLES-12345OTHER"),
      packed("BASLUS-20152AC04"),
      packed("BEDATA-SYSTEM"),
      packed("BASLUS-20152SYS"),
    )
    assertSameBytes(sigilUnit(ACE_COMBAT), unit(cards, ACE_COMBAT).read()?.bytes, "Same unit")
  }

  @Test
  fun restoresIntoAFileCardLeavingOtherGamesAlone() {
    val cards = memcards("file")
    val card = File(cards, "Mcd001.ps2")
    val other = packed("BESLES-12345OTHER")
    fileCard(
      card,
      other,
      save("BASLUS-20152AC04", "AC04.DAT" to "old progress"),
      save("BASLUS-20152OLD", "OLD.DAT" to "a save RomM's copy lacks"),
    )
    val unit = unit(cards, ACE_COMBAT)
    val before = assertNotNull(unit.read())
    val kept = mutableListOf<LocalCopy>()

    unit.write(before.file, sigilUnit(ACE_COMBAT), before.hash) { kept += it }

    assertTrue(kept.isEmpty(), "The history already has what the pull replaced")
    assertEquals(setOf("BESLES-12345OTHER", "BASLUS-20152AC04", "BASLUS-20152SYS"), names(card))
    assertTrue(Ps2Card.load(card.readBytes()).holds(other), "Another game's save is as it was")
    assertSameBytes(sigilUnit(ACE_COMBAT), unit.read()?.bytes, "The game's saves are RomM's")
    assertEquals(listOf("Mcd001.ps2"), cards.list()!!.toList(), "No temp files are left")
  }

  @Test
  fun restoresIntoAFolderCardAsPocketGoalKeepsOne() {
    val cards = memcards("pocketgoal")
    val card = File(cards, "Mcd001.ps2")
    val other = folderCardFiles.getValue("BESLES-12345OTHER/OTHER.DAT")
    writeTree(
      card,
      mapOf(
        "BASCUS-97124AYBABTU!/bank0" to "old progress".encodeToByteArray(),
        "BASCUS-97124AYBABTU!/stray" to "not in RomM's copy".encodeToByteArray(),
        "BESLES-12345OTHER/OTHER.DAT" to other,
      ),
    )
    val unit = unit(cards, JAK, folderCards = true)
    val before = assertNotNull(unit.read())

    unit.write(before.file, sigilUnit(JAK), before.hash) {}

    val tree = readTree(card)
    val jak = "BASCUS-97124AYBABTU!/"
    assertEquals(
      folderCardFiles.filterKeys { it.startsWith(jak) }.mapValues { md5(it.value) },
      tree
        .filterKeys { it.startsWith(jak) && !it.endsWith("_pcsx2_index") }
        .mapValues { md5(it.value) },
      "Jak's folder holds RomM's files, and the file RomM's copy lacks is gone",
    )
    assertTrue("${jak}_pcsx2_index" in tree, "PCSX2's index is written beside them")
    assertSameBytes(other, tree["BESLES-12345OTHER/OTHER.DAT"], "Another game's save is as it was")
    assertTrue(
      Ps2FolderCard.superblockUsable(tree[Ps2FolderCard.SUPERBLOCK]),
      "PCSX2 reads the card",
    )
    assertSameBytes(sigilUnit(JAK), unit.read()?.bytes, "The game's saves are RomM's")
    assertEquals(listOf("Mcd001.ps2"), cards.list()!!.toList(), "No temp folders are left")
  }

  @Test
  fun aPullMakesTheCardTheEmulatorWouldRead() {
    // PocketGOAL reads only folder cards; ARMSX2 makes file cards.
    for (folderCards in listOf(true, false)) {
      val cards = memcards("new-$folderCards")
      val unit = unit(cards, JAK, folderCards)
      assertNull(unit.read())
      val target = assertNotNull(unit.target("Game (USA).ps2"))
      assertEquals(File(cards, "Mcd001.ps2"), target)

      unit.write(target, sigilUnit(JAK), null) {}

      assertEquals(folderCards, target.isDirectory)
      assertSameBytes(sigilUnit(JAK), unit.read()?.bytes, "The new card holds RomM's saves")
    }
    assertNull(unit(File(root, "missing"), JAK).target("Game (USA).ps2"), "No memcards folder")
  }

  @Test
  fun aSaveGoesBackToTheCardItWasOn() {
    val cards = memcards("two")
    fileCard(File(cards, "Mcd001.ps2"), packed("BESLES-12345OTHER"))
    // ARMSX2 names its cards in lower case.
    fileCard(File(cards, "mcd002.ps2"), save("BASLUS-20152AC04", "AC04.DAT" to "old progress"))
    val unit = unit(cards, ACE_COMBAT)
    val before = assertNotNull(unit.read())
    assertEquals(File(cards, "mcd002.ps2"), before.file)

    unit.write(before.file, sigilUnit(ACE_COMBAT), before.hash) {}

    assertEquals(setOf("BESLES-12345OTHER", "BASLUS-20152SYS"), names(File(cards, "Mcd001.ps2")))
    assertEquals(setOf("BASLUS-20152AC04"), names(File(cards, "mcd002.ps2")))
    assertTrue(Ps2Card.load(File(cards, "Mcd001.ps2").readBytes()).holds(packed("BASLUS-20152SYS")))
    assertTrue(
      Ps2Card.load(File(cards, "mcd002.ps2").readBytes()).holds(packed("BASLUS-20152AC04")),
    )
  }

  @Test
  fun keepsWhatTheEmulatorWroteAfterTheHistoryCopy() {
    val cards = memcards("gap")
    val card = File(cards, "Mcd001.ps2")
    fileCard(card, save("BASLUS-20152AC04", "AC04.DAT" to "old progress"))
    val unit = unit(cards, ACE_COMBAT)
    val snapshot = assertNotNull(unit.read())
    fileCard(card, save("BASLUS-20152AC04", "AC04.DAT" to "written in the gap"))
    val kept = mutableListOf<LocalCopy>()

    unit.write(snapshot.file, sigilUnit(ACE_COMBAT), snapshot.hash) { kept += it }

    val gap = Ps2Card.load(kept.single().bytes)
    assertTrue(gap.holds(save("BASLUS-20152AC04", "AC04.DAT" to "written in the gap")))
  }

  // Deleting a save frees its files' whole chains, so a chain that runs on into another save's
  // clusters makes both unsafe to touch, even past what the file's length needs.
  @Test
  fun aSaveWhoseChainRunsIntoAnothersIsCorrupt() {
    val card = Ps2Card.format()
    card.inject(packed("BASLUS-20152AC04"))
    card.inject(packed("BESLES-12345OTHER"))
    val (first, second) = card.list().entries.sortedBy { it.name }
    fun dataFiles(entry: Ps2Entry) =
      card.extract(entry.firstCluster).files.filter { u32(it.entry, LENGTH) > 0 }
    val tail = assertNotNull(card.chain(u32(dataFiles(first).last().entry, CLUSTER))).last()
    card.fatSet(tail, ALLOCATED or u32(dataFiles(second).first().entry, CLUSTER))

    val listing = card.list()
    assertTrue(first.name in listing.corrupt, "${listing.corrupt}")
    assertTrue(second.name in listing.corrupt, "${listing.corrupt}")
  }

  @Test
  fun refusesAUnitWithNoneOfTheGamesSaves() {
    val cards = memcards("refuse")
    val card = File(cards, "Mcd001.ps2")
    fileCard(card, save("BASLUS-20152AC04", "AC04.DAT" to "progress"))
    val before = card.readBytes()
    val unit = unit(cards, ACE_COMBAT)
    val local = assertNotNull(unit.read())

    assertFailsWith<IOException> { unit.write(local.file, sigilUnit(JAK), local.hash) {} }
    assertFailsWith<IOException> {
      unit.write(local.file, "not a card".encodeToByteArray(), local.hash) {}
    }
    assertSameBytes(before, card.readBytes(), "Nothing was written")
  }

  @Test
  fun aFileThatIsntACardIsNeverTakenAsNoSaves() {
    val cards = memcards("junk")
    File(cards, "Mcd001.ps2").writeText("not a card")
    assertFailsWith<IOException> { unit(cards, ACE_COMBAT).read() }
  }

  @Test
  fun anErasedCardHoldsNothingAndAPullFormatsIt() {
    val cards = memcards("erased")
    val card = File(cards, "Mcd001.ps2").apply { writeBytes(ByteArray(8650752) { -1 }) }
    val unit = unit(cards, JAK)
    assertNull(unit.read())

    unit.write(assertNotNull(unit.target("Game (USA).ps2")), sigilUnit(JAK), null) {}

    assertEquals(setOf("BASCUS-97124AYBABTU!"), names(card))
  }

  @Test
  fun readsTheShapesOlderClientsUploaded() {
    val withMarker =
      sigilUnit(ACE_COMBAT) +
        """{"h":true}""".encodeToByteArray() +
        byteArrayOf(10, 0, 0, 0) +
        "ARGOSY".encodeToByteArray() +
        byteArrayOf(1, 0)
    val zipped = ByteArrayOutputStream()
    ZipOutputStream(zipped).use { zip ->
      for ((path, data) in folderCardFiles.filterKeys { it.startsWith("BASLUS-20152") }) {
        zip.putNextEntry(ZipEntry("Mcd001.ps2/$path"))
        zip.write(data)
        zip.closeEntry()
      }
    }
    for (unitBytes in listOf(withMarker, zipped.toByteArray())) {
      val cards = memcards("legacy-${unitBytes.size}")
      val unit = unit(cards, ACE_COMBAT)
      unit.write(assertNotNull(unit.target("Game (USA).ps2")), unitBytes, null) {}
      assertSameBytes(sigilUnit(ACE_COMBAT), unit.read()?.bytes, "The same saves as sigil's unit")
    }
  }

  @Test
  fun aFolderKeepsWhatItsIndexCantSayInMetaFiles() {
    val card = Ps2Card.format()
    // A name PCSX2 cleans on the host, and a mode it wouldn't rebuild.
    val plain = save("BASLUS-20152AC04", "A_B" to "data")
    val odd = plain.files.single().entry.copyOf()
    "A:B".encodeToByteArray().copyInto(odd, NAME)
    putU16(odd, MODE, 0x8417)
    val save =
      Ps2Save(plain.entry, plain.self, plain.parent, listOf(Ps2File(odd, plain.files[0].data)))
    card.inject(save)

    val files = Ps2FolderCard.unpack(save).toMap()
    assertEquals(setOf("A_B", "_pcsx2_meta/A_B", "_pcsx2_index"), files.keys)
    val back = Ps2FolderCard.pack("BASLUS-20152AC04", files.toList())
    assertEquals(save.md5(), back.md5())
    assertTrue(card.holds(back), "The folder reads back as the save it was")
  }
}
