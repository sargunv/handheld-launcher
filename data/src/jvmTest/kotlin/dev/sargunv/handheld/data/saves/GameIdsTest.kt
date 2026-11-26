package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.saves.GameIdKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads ids from tiny images built here, with only the headers the reader looks at. */
class GameIdsTest {
  private val root = Files.createTempDirectory("game-ids-test").toFile()

  @AfterTest fun deleteRoot() = check(root.deleteRecursively())

  private fun file(name: String, bytes: ByteArray) = File(root, name).apply { writeBytes(bytes) }

  private fun image(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

  private fun ByteBuffer.ascii(at: Int, text: String) = apply {
    text.encodeToByteArray().forEachIndexed { i, b -> put(at + i, b) }
  }

  // PSP

  /** An ISO 9660 image whose root holds UMD_DATA.BIN with [umd]. */
  private fun pspIso(umd: String): ByteArray =
    image(20 * 2048) {
      // The volume descriptor's root directory record: sector 18.
      put(16 * 2048, 1).ascii(16 * 2048 + 1, "CD001")
      putInt(16 * 2048 + 156 + 2, 18).putInt(16 * 2048 + 156 + 10, 2048)
      // The root directory: one record for the file, at sector 19.
      val record = 18 * 2048
      val name = "UMD_DATA.BIN;1"
      put(record, (33 + name.length).toByte())
      putInt(record + 2, 19).putInt(record + 10, umd.length)
      put(record + 32, name.length.toByte()).ascii(record + 33, name)
      ascii(19 * 2048, umd)
    }

  @Test
  fun readsAPspSerialFromAnIso() {
    val iso = file("game.iso", pspIso("ULUS-10064|0001|G|"))
    assertEquals("ULUS10064", GameIds.read(iso, GameIdKind.Psp))
  }

  @Test
  fun readsAPspSerialFromACso() {
    val iso = pspIso("NPJH-50804|0001|G|")
    val blockSize = 2048
    val blocks = iso.toList().chunked(blockSize).map { it.toByteArray() }
    val data = ByteArrayOutputStream()
    val index = mutableListOf<Int>()
    val start = 0x18 + (blocks.size + 1) * 4
    for (block in blocks) {
      index += start + data.size()
      val deflater =
        Deflater(9, true).apply {
          setInput(block)
          finish()
        }
      val out = ByteArray(blockSize * 2)
      data.write(out, 0, deflater.deflate(out))
      deflater.end()
    }
    index += start + data.size()
    val header =
      image(start) {
        ascii(0, "CISO").putInt(4, 0x18).putLong(8, iso.size.toLong()).putInt(0x10, blockSize)
        put(0x14, 1)
        index.forEachIndexed { i, offset -> putInt(0x18 + i * 4, offset) }
      }
    val cso = file("game.cso", header + data.toByteArray())
    assertEquals("NPJH50804", GameIds.read(cso, GameIdKind.Psp))
  }

  @Test
  fun refusesAPspImageWithoutASerial() {
    assertNull(GameIds.read(file("game.iso", pspIso("nothing here")), GameIdKind.Psp))
    assertNull(GameIds.read(file("short.iso", ByteArray(100)), GameIdKind.Psp))
  }

  // 3DS

  private fun ncch(programId: Long) =
    image(0x200) {
      ascii(0x100, "NCCH")
      putLong(0x118, programId)
    }

  @Test
  fun readsA3dsTitleIdFromEachContainer() {
    val id = 0x0004000000033500L
    val ncsd =
      image(0x4000) {
        ascii(0x100, "NCSD")
        putInt(0x120, 0x4000 / 0x200)
      } + ncch(id)
    assertEquals("0004000000033500", GameIds.read(file("game.3ds", ncsd), GameIdKind.N3ds))
    assertEquals("0004000000033500", GameIds.read(file("game.cxi", ncch(id)), GameIdKind.N3ds))

    // A CIA's sections each start on a 64-byte boundary: header, certificates, ticket, TMD.
    val cia =
      image(0x3900) {
        putInt(0, 0x2020).putInt(0x08, 0xA00).putInt(0x0C, 0x350).putInt(0x10, 0xB34)
      } + ncch(id)
    assertEquals("0004000000033500", GameIds.read(file("game.cia", cia), GameIdKind.N3ds))
  }

  @Test
  fun readsA3dsTitleIdFromACompressedImage() {
    val inner = ncch(0x0004000000055E00L)
    val header =
      image(0x20) {
        ascii(0, "Z3DS").ascii(4, "NCCH")
        put(8, 1)
        putShort(0x0A, 0x20).putInt(0x0C, 0x10)
      }
    val z3ds = file("game.zcxi", header + ByteArray(0x10) + "compressed".encodeToByteArray())
    var asked: Long? = null
    val zstd = Zstd { _, offset, _ ->
      asked = offset
      inner
    }
    assertEquals("0004000000055E00", GameIds.read(z3ds, GameIdKind.N3ds, zstd = zstd))
    assertEquals(0x30L, asked, "The payload starts after the header and the metadata")
    assertNull(GameIds.read(z3ds, GameIdKind.N3ds), "Without zstd it can't be read")
  }

  @Test
  fun refusesA3dsTitleThatIsntRetail() {
    assertNull(GameIds.read(file("hb.cxi", ncch(0x0001000000000000L)), GameIdKind.N3ds))
  }

  // Switch

  /** A PFS0 partition holding [files]. */
  private fun pfs0(vararg files: Pair<String, ByteArray>): ByteArray {
    val names = files.joinToString("") { it.first + "\u0000" }.encodeToByteArray()
    val strings = names.copyOf((names.size + 15) / 16 * 16)
    var offset = 0L
    var nameAt = 0
    val head =
      image(16 + files.size * 0x18) {
        ascii(0, "PFS0").putInt(4, files.size).putInt(8, strings.size)
        files.forEachIndexed { i, (name, bytes) ->
          putLong(16 + i * 0x18, offset).putLong(16 + i * 0x18 + 8, bytes.size.toLong())
          putInt(16 + i * 0x18 + 16, nameAt)
          offset += bytes.size
          nameAt += name.length + 1
        }
      }
    return files.fold(head + strings) { acc, (_, bytes) -> acc + bytes }
  }

  private fun ticket(titleId: Long): ByteArray =
    image(0x2C0) {
      putInt(0, 0x10004)
      order(ByteOrder.BIG_ENDIAN).putLong(0x140 + 0x160, titleId).order(ByteOrder.LITTLE_ENDIAN)
    }

  @Test
  fun readsASwitchIdFromAnNspTicket() {
    val nsp = pfs0("0100abcd12345000000000000000000a.tik" to ticket(0x0100ABCD12345000L))
    assertEquals("0100ABCD12345000", GameIds.read(file("game.nsp", nsp), GameIdKind.Switch))
  }

  @Test
  fun readsAnUpdateOrAddOnAsItsGame() {
    val update = pfs0("u.tik" to ticket(0x0100ABCD12345800L))
    assertEquals("0100ABCD12345000", GameIds.read(file("update.nsp", update), GameIdKind.Switch))
    val addOn = pfs0("a.tik" to ticket(0x0100152000023001L))
    assertEquals("0100152000022000", GameIds.read(file("dlc.nsp", addOn), GameIdKind.Switch))
    val twoGames =
      pfs0("a.tik" to ticket(0x0100ABCD12345000L), "b.tik" to ticket(0x0100000000010000L))
    assertNull(GameIds.read(file("two.nsp", twoGames), GameIdKind.Switch))
  }

  @Test
  fun readsASwitchIdFromAnNcaWithTheEmulatorsKeys() {
    val key = ByteArray(32) { (it * 7 + 3).toByte() }
    val keys =
      file(
        "prod.keys",
        ("master_key_00 = 00\nheader_key = " + key.joinToString("") { "%02x".format(it) } + "\n")
          .encodeToByteArray(),
      )
    val plain =
      image(0x200) {
        ascii(0, "NCA3")
        putLong(0x10, 0x0100ABCD12345000L)
      }
    val nca = ByteArray(0x200) + GameIds.xts(plain, key, sector = 1, Cipher.ENCRYPT_MODE)
    val nsp = file("game.nsp", pfs0("0123.nca" to nca))
    assertEquals("0100ABCD12345000", GameIds.read(nsp, GameIdKind.Switch, switchKeys = keys))
    assertNull(GameIds.read(nsp, GameIdKind.Switch), "Without keys or a ticket there's no id")
  }

  @Test
  fun readsAGamesSecondProgramAsItsFirstButAnAddOnAsItsGame() {
    val key = ByteArray(32) { (it * 7 + 3).toByte() }
    val keys =
      file(
        "prod.keys",
        ("master_key_00 = 00\nheader_key = " + key.joinToString("") { "%02x".format(it) } + "\n")
          .encodeToByteArray(),
      )
    fun nca(id: Long, contentType: Int): ByteArray {
      val plain =
        image(0x200) {
          ascii(0, "NCA3")
          put(5, contentType.toByte())
          putLong(0x10, id)
        }
      return ByteArray(0x200) + GameIds.xts(plain, key, sector = 1, Cipher.ENCRYPT_MODE)
    }
    // A game of two programs: the second's id ends in 1.
    val game = pfs0("a.nca" to nca(0x010049900F546000L, 0), "b.nca" to nca(0x010049900F546001L, 0))
    assertEquals(
      "010049900F546000",
      GameIds.read(file("game.nsp", game), GameIdKind.Switch, switchKeys = keys),
    )
    // An add-on's data with the same id belongs to the game a block below.
    val addOn = pfs0("c.nca" to nca(0x010049900F546001L, 4))
    assertEquals(
      "010049900F545000",
      GameIds.read(file("dlc.nsp", addOn), GameIdKind.Switch, switchKeys = keys),
    )
  }

  @Test
  fun readsASwitchIdFromAnXcisSecurePartition() {
    // A game card carries a ticket only for an update bundled with the game.
    val root =
      hfs0("normal" to ByteArray(0), "secure" to hfs0("u.tik" to ticket(0x0100ABCD12345800L)))
    val xci =
      image(0x200) {
        ascii(0x100, "HEAD")
        putLong(0x130, 0x200)
      } + root
    assertEquals("0100ABCD12345000", GameIds.read(file("game.xci", xci), GameIdKind.Switch))
  }

  private fun hfs0(vararg files: Pair<String, ByteArray>): ByteArray {
    val names = files.joinToString("") { it.first + "\u0000" }.encodeToByteArray()
    var offset = 0L
    var nameAt = 0
    val head =
      image(16 + files.size * 0x40) {
        ascii(0, "HFS0").putInt(4, files.size).putInt(8, names.size)
        files.forEachIndexed { i, (name, bytes) ->
          putLong(16 + i * 0x40, offset).putLong(16 + i * 0x40 + 8, bytes.size.toLong())
          putInt(16 + i * 0x40 + 16, nameAt)
          offset += bytes.size
          nameAt += name.length + 1
        }
      }
    return files.fold(head + names) { acc, (_, bytes) -> acc + bytes }
  }

  // GameCube and Wii

  private fun discHeader(code: String, wii: Boolean) =
    image(0x20) {
      ascii(0, code + "01")
      order(ByteOrder.BIG_ENDIAN)
      if (wii) putInt(0x18, 0x5D1C9EA3) else putInt(0x1C, 0xC2339F3D.toInt())
    }

  @Test
  fun readsADiscsGameCodeFromEachContainer() {
    val gc = discHeader("GZLE", wii = false)
    val wii = discHeader("RSBE", wii = true)
    assertEquals("GZLE", GameIds.read(file("gc.iso", gc), GameIdKind.Disc))
    assertEquals("RSBE", GameIds.read(file("wii.iso", wii), GameIdKind.Disc))
    val rvz = image(0x58) { ascii(0, "RVZ").put(3, 1) } + wii
    assertEquals("RSBE", GameIds.read(file("wii.rvz", rvz), GameIdKind.Disc))
    val wbfs = image(0x200) { ascii(0, "WBFS").put(8, 9) } + wii
    assertEquals("RSBE", GameIds.read(file("wii.wbfs", wbfs), GameIdKind.Disc))

    // A GCZ's first block, compressed with zlib.
    val block = gc + ByteArray(0x3E0)
    val deflater =
      Deflater().apply {
        setInput(block)
        finish()
      }
    val compressed = ByteArray(0x400).let { it.copyOf(deflater.deflate(it)) }
    val gcz =
      image(32 + 12) {
        putInt(0, 0xB10BC001.toInt()).putLong(8, compressed.size.toLong())
        putInt(24, block.size).putInt(28, 1)
        putLong(32, 0)
      } + compressed
    assertEquals("GZLE", GameIds.read(file("gc.gcz", gcz), GameIdKind.Disc))
  }

  @Test
  fun refusesADiscWithoutAConsolesMagic() {
    assertNull(
      GameIds.read(
        file("junk.iso", "GZLE01".encodeToByteArray() + ByteArray(0x40)),
        GameIdKind.Disc,
      ),
    )
  }
}
