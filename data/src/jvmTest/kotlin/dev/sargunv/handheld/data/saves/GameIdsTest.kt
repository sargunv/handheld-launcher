package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.saves.GameIdKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
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

  private fun switchId(file: File, keys: File? = null) =
    GameIds.read(file, GameIdKind.Switch, keys) { error("A Switch game's id isn't in a header") }

  @Test
  fun takesOnlyARetail3dsTitlesId() {
    val game = File(root, "game.cxi")
    assertEquals("0004000000033500", GameIds.read(game, GameIdKind.N3ds) { "0004000000033500" })
    assertNull(GameIds.read(game, GameIdKind.N3ds) { "0001000000000000" })
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
    assertEquals("0100ABCD12345000", switchId(file("game.nsp", nsp)))
  }

  @Test
  fun readsAnUpdateOrAddOnAsItsGame() {
    val update = pfs0("u.tik" to ticket(0x0100ABCD12345800L))
    assertEquals("0100ABCD12345000", switchId(file("update.nsp", update)))
    val addOn = pfs0("a.tik" to ticket(0x0100152000023001L))
    assertEquals("0100152000022000", switchId(file("dlc.nsp", addOn)))
    val twoGames =
      pfs0("a.tik" to ticket(0x0100ABCD12345000L), "b.tik" to ticket(0x0100000000010000L))
    assertNull(switchId(file("two.nsp", twoGames)))
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
    assertEquals("0100ABCD12345000", switchId(nsp, keys))
    assertNull(switchId(nsp), "Without keys or a ticket there's no id")
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
      switchId(file("game.nsp", game), keys),
    )
    // An add-on's data with the same id belongs to the game a block below.
    val addOn = pfs0("c.nca" to nca(0x010049900F546001L, 4))
    assertEquals(
      "010049900F545000",
      switchId(file("dlc.nsp", addOn), keys),
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
    assertEquals("0100ABCD12345000", switchId(file("game.xci", xci)))
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
}
