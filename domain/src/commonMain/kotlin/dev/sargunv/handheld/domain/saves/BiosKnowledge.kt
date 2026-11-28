// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog.ARCADE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.DREAMCAST
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NDS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS3
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSX
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SATURN
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SEGA_CD
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SWITCH
import dev.sargunv.handheld.domain.platform.PlatformCatalog.TG_CD
import dev.sargunv.handheld.domain.platform.PlatformCatalog.XBOX
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * Where an emulator reads firmware from.
 *
 * [paths] are candidate folders, most likely first, written with [SavePaths] placeholders.
 * [platforms] are the platforms it reads firmware for; null means any, for a flat system folder
 * like RetroArch's. [renames] maps a firmware file name to the name this emulator insists on.
 */
data class BiosLocation(
  val emulator: EmulatorId,
  val paths: List<String>,
  val platforms: Set<PlatformSlug>?,
  val renames: Map<String, String> = emptyMap(),
) {
  /** The name [fileName] must have here, ignoring case when matching. */
  fun targetName(fileName: String): String =
    renames.entries.firstOrNull { it.key.equals(fileName, ignoreCase = true) }?.value ?: fileName

  /**
   * The name the firmware file [fileName], whose hash is [md5], takes here. A flat system folder
   * holds firmware for libretro cores, which look it up by the names in
   * [BiosKnowledge.libretroNames].
   */
  fun targetName(fileName: String, md5: String?): String =
    if (platforms == null) {
      md5?.let { BiosKnowledge.libretroNames[it.lowercase()] } ?: fileName
    } else {
      targetName(fileName)
    }

  /** [paths] with [storage] and [packageName], the emulator's installed build, filled in. */
  fun folders(storage: String, packageName: String): List<String> = paths.map {
    it.replace(SavePaths.STORAGE, storage).replace(SavePaths.PACKAGE, packageName)
  }
}

/** Which platforms need firmware, and where each known emulator reads it. */
object BiosKnowledge {
  private fun id(value: String) = EmulatorId(value)

  /** The platforms that need firmware. A twin needs what its platform does. */
  private val needFirmware: Set<PlatformSlug> =
    setOf(PSX, SATURN, SEGA_CD, DREAMCAST, ARCADE, NDS, GBA, TG_CD, PS2, PS3, SWITCH, XBOX)

  /**
   * The names libretro cores insist on, by firmware MD5. Cores look firmware up by exact name, so a
   * file copied into a libretro system folder takes this name; a path holds the subfolder it goes
   * in.
   */
  val libretroNames: Map<String, String> =
    mapOf(
      "924e392ed05558ffdb115408c263dccf" to "scph1001.bin",
      "8dd7d5296a650fac7319bce665a6a53c" to "scph5500.bin",
      "490f666e1afb15b7362b406ed1cea246" to "scph5501.bin",
      "32736f17079d0b2b7024407c39bd3050" to "scph5502.bin",
      "1e68c231d0896b7eadcad1d7d8e76129" to "scph7001.bin",
      "b9d9a0286c33dc6b7237bb13cd46fdee" to "scph101.bin",
      "e10c53c2f8b90bab96ead2d368858623" to "dc/dc_boot.bin",
      "0a93f7940c455905bea6e392dfde92a4" to "dc/dc_flash.bin",
      "af5828fdff51384f99b3c4926be27762" to "sega_101.bin",
      "3240872c70984b6cbfda1586cab68dbe" to "mpr-17933.bin",
      "85ec9ca47d8f6f9ab5af2d1234c832d4" to "mpr-18811-mx.ic1",
      "f273555d7d91e8a5a6bfd9bcf066331c" to "mpr-19367-mx.ic1",
      "2efd74e3232ff260e371b99f84024f7f" to "bios_CD_U.bin",
      "854b9150240a198070150e4566ae1290" to "bios_CD_U.bin",
      "e66fa1dc5820d254611fdcdba0662372" to "bios_CD_E.bin",
      "278a9397d192149e84e820ac621a8edd" to "bios_CD_J.bin",
      "a860e8c0b6d573d191e4ec7db1b1e4f6" to "gba_bios.bin",
      "24f67bdea115a2c847c8813a628571b3" to "bios7.bin",
      "df692a80a5b1bc90728bc3dfc76cd948" to "bios7.bin",
      "a392174eb3e572fed6447e956bde4b25" to "bios9.bin",
      "145eaef5bd3037cbc247c213bb3da1b3" to "firmware.bin",
      "94bc5094607c5e6598d50472c52f27f2" to "firmware.bin",
      "38179df8f4ac870017db21ebcbf53114" to "syscard3.pce",
      "dffb72f116d36d025068b23970a4f6df" to "neogeo.zip",
    )

  private fun retroArch(id: String, packageName: String) =
    BiosLocation(
      id(id),
      listOf("{storage}/RetroArch/system", "{storage}/Android/data/$packageName/files/system"),
      platforms = null,
    )

  val locations: List<BiosLocation> =
    listOf(
      retroArch("retroarch", "com.retroarch"),
      retroArch("retroarch_64", "com.retroarch.aarch64"),
      retroArch("retroarch_32", "com.retroarch.ra32"),
      BiosLocation(
        id("duckstation"),
        listOf("{storage}/Android/data/com.github.stenzek.duckstation/files/bios"),
        setOf(PSX),
      ),
      BiosLocation(
        id("armsx1"),
        listOf("{storage}/Android/data/com.nanodata.armsx/files/bios"),
        setOf(PSX),
      ),
      BiosLocation(
        id("hakux"),
        listOf("{storage}/Android/data/com.rfandango.haku_x/files/x1box"),
        setOf(XBOX),
        renames =
          mapOf(
            "Complex_4627.bin" to "flash.bin",
            "Complex_4627v1.03.bin" to "flash.bin",
            "mcpx_1.0.bin" to "mcpx.bin",
          ),
      ),
      BiosLocation(
        id("melonds"),
        listOf("{storage}/melonDS/bios", "{storage}/Android/data/me.magnum.melonds/files/bios"),
        setOf(NDS),
      ),
      BiosLocation(
        id("melondualds"),
        listOf("{storage}/melonDS/bios", "{storage}/Android/data/me.magnum.melondualds/files/bios"),
        setOf(NDS),
      ),
      BiosLocation(
        id("flycast"),
        listOf("{storage}/Android/data/com.flycast.emulator/files/data", "{storage}/Flycast/data"),
        setOf(DREAMCAST, ARCADE),
      ),
      BiosLocation(
        id("redream"),
        listOf("{storage}/Android/data/io.recompiled.redream/files"),
        setOf(DREAMCAST),
      ),
      BiosLocation(
        id("saturn_emu"),
        listOf("{storage}/Android/data/com.explusalpha.SaturnEmu/files"),
        setOf(SATURN),
      ),
      BiosLocation(
        id("pizza_boy_gba"),
        listOf("{storage}/PizzaBoyGBA", "{storage}/Android/data/it.dbtecno.pizzaboygba/files"),
        setOf(GBA),
      ),
      BiosLocation(
        id("pizza_boy_gba_pro"),
        listOf("{storage}/PizzaBoyGBA", "{storage}/Android/data/it.dbtecno.pizzaboygbapro/files"),
        setOf(GBA),
      ),
      BiosLocation(
        id("nethersx2"),
        listOf("{storage}/Android/data/{package}/files/bios"),
        setOf(PS2),
      ),
      BiosLocation(
        id("armsx2"),
        listOf("{storage}/Android/data/com.armsx2/files/bios"),
        setOf(PS2),
      ),
      BiosLocation(id("eden"), listOf("{storage}/Android/data/{package}/files"), setOf(SWITCH)),
    )

  /** Where [emulator] reads firmware, or null when it isn't known. */
  fun locationFor(emulator: EmulatorId): BiosLocation? = locations.firstOrNull {
    it.emulator == emulator
  }

  /**
   * Where [emulator] reads the firmware [platform] needs, or null when the platform needs none, or
   * the emulator's firmware folder for it isn't known.
   */
  fun locationFor(emulator: EmulatorId, platform: PlatformSlug): BiosLocation? {
    val target = platform.emulatedAs
    if (target !in needFirmware) return null
    val location = locationFor(emulator) ?: return null
    val platforms = location.platforms ?: return location
    return location.takeIf { target in platforms }
  }
}
