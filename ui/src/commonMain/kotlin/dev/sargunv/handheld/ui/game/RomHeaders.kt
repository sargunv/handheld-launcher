package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Rom
import kotlin.math.roundToLong

/** One field of a game's own header, such as its serial, as the header stores it. */
data class RomHeaderField(val key: Key, val value: Value) {
  /** Which field it is. Its constants are the session's `HeaderKey`'s. */
  enum class Key(val label: String) {
    TITLE("Title"),
    GAME_CODE("Game code"),
    GAME_ID("Game ID"),
    PRODUCT_CODE("Product code"),
    PRODUCT_NUMBER("Product number"),
    SERIAL("Serial"),
    DISC_ID("Disc ID"),
    TITLE_ID("Title ID"),
    EXECUTABLE("Executable"),
    MAKER("Maker"),
    MANUFACTURER("Manufacturer"),
    LICENSEE("Licensee"),
    COPYRIGHT("Copyright"),
    REGION("Region"),
    REGIONS("Regions"),
    AREAS("Areas"),
    VERSION("Version"),
    RELEASED("Released"),
    DISC("Disc"),
    HEADER_FORMAT("Header"),
    MAPPER("Mapper"),
    SUBMAPPER("Submapper"),
    PRG_ROM("PRG ROM"),
    CHR_ROM("CHR ROM"),
    ROM_SIZE("ROM"),
    TIMING("Timing"),
    BATTERY("Battery"),
    MAP("Map"),
    BYTE_ORDER("Byte order"),
    SYSTEM("System"),
    CONSOLE("Console"),
    CARTRIDGE("Cartridge"),
    FORMAT("Format"),
    COMPRESSED("Compressed"),
    NEW3DS_ONLY("New 3DS only"),
    USES32X("32X"),
    HEADER_CHECKSUM("Header checksum"),
    VIDEO("Video"),
    CATEGORY("Category"),
    SYSTEM_SOFTWARE("System software"),
  }

  sealed interface Value {
    /** Text from the header, or the format's name for a code it stores. */
    data class Text(val text: String) : Value

    /** The format's names for several codes, such as the regions a disc plays in. */
    data class Names(val names: List<String>) : Value

    data class Number(val number: Long) : Value

    data class Size(val bytes: Long) : Value

    /** Whether a flag is set, or whether a checksum matches. */
    data class Flag(val set: Boolean) : Value

    /** A number the format writes in hex, with [digits] digits. */
    data class Hex(val number: ULong, val digits: Int) : Value

    data class Date(val year: Int, val month: Int, val day: Int) : Value
  }
}

/** The field in words, as the Game screen shows it. */
internal fun RomHeaderField.text(): String {
  val value =
    when (value) {
      is RomHeaderField.Value.Text -> value.text
      is RomHeaderField.Value.Names -> value.names.joinToString(", ")
      is RomHeaderField.Value.Number -> value.number.toString()
      is RomHeaderField.Value.Size -> binarySize(value.bytes)
      is RomHeaderField.Value.Flag ->
        when {
          key == RomHeaderField.Key.HEADER_CHECKSUM -> if (value.set) "Valid" else "Doesn't match"
          value.set -> "Yes"
          else -> "No"
        }
      is RomHeaderField.Value.Hex ->
        value.number.toString(16).uppercase().padStart(value.digits, '0')
      is RomHeaderField.Value.Date ->
        "${value.year}-${value.month.toString().padStart(2, '0')}-${value.day.toString().padStart(2, '0')}"
    }
  return "${key.label} $value"
}

/** A size in bytes, KiB, MiB or GiB. */
private fun binarySize(bytes: Long): String {
  val units = listOf("bytes", "KiB", "MiB", "GiB")
  var size = bytes.toDouble()
  var unit = 0
  while (size >= 1024 && unit < units.lastIndex) {
    size /= 1024
    unit++
  }
  val tenths = (size * 10).roundToLong()
  val number = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10.0}"
  return "$number ${units[unit]}"
}

/**
 * Reads what a game's file says about itself in its own header, such as a disc's serial or a
 * cartridge's region, for the Game screen. Each platform supplies its own.
 */
fun interface RomHeaders {
  /**
   * The fields of the header of [rom]'s file or folder at [path], or none when there's none to
   * read. It reads the file, so it runs off the main thread.
   */
  suspend fun read(rom: Rom, path: String): List<RomHeaderField>

  companion object {
    /** For platforms that don't read headers. */
    val None = RomHeaders { _, _ -> emptyList() }
  }
}
