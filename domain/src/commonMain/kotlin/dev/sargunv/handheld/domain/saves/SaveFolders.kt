// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// The save areas are ported from argosy-sigil's layouts (https://github.com/rommapp/argosy-sigil,
// MPL-2.0,
// src/save_layout.c), so a save moves between Argosy and the launcher in the same zip.

package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug

/** Which of a folder's entries, by name, belong to a game's save. */
sealed interface SaveSelection {
  fun claims(name: String): Boolean

  /** Every entry, but for [except]: files the emulator keeps beside the save, not in it. */
  data class Whole(val except: Set<String> = emptySet()) : SaveSelection {
    override fun claims(name: String) = name !in except
  }

  /** The entries whose name starts with [prefix], such as each of a PSP game's save folders. */
  data class Prefixed(val prefix: String) : SaveSelection {
    override fun claims(name: String) = name.startsWith(prefix)
  }

  /** The GameCube saves of the game [gameId], as Dolphin names them: `<maker>-<game id>-...gci`. */
  data class GciFiles(val gameId: String) : SaveSelection {
    override fun claims(name: String) =
      name.endsWith(".gci", ignoreCase = true) && name.split('-').getOrNull(1) == gameId
  }
}

/**
 * One folder that holds part of a game's save.
 *
 * @param folder the folder, relative to the emulator's save folder. A Switch save's holds
 *   [SaveFolders.PROFILE] for the profile.
 * @param zipPrefix where the folder's entries sit in the zip the save syncs as.
 * @param selection which of the folder's entries are the game's.
 */
data class SaveArea(val folder: String, val zipPrefix: String, val selection: SaveSelection)

/**
 * A game's save that an emulator keeps as folders or as several files, which syncs as one zip named
 * [name].
 */
data class FolderSave(val areas: List<SaveArea>, val name: String)

/** What kind of id a game's save is filed under, which the launcher reads from the game's file. */
enum class GameIdKind {
  /** A PSP disc's serial, such as `ULUS10064`. */
  Psp,

  /** A 3DS title id in 16 hex digits, such as `0004000000033500`. */
  N3ds,

  /** A Switch game's application id in 16 hex digits, such as `0100ABCD12345000`. */
  Switch,

  /** A GameCube or Wii disc's game code, four characters such as `GZLE`. */
  Disc,

  /**
   * A PS2 disc's serial as its save folders carry it, such as `SLUS-20152`. Its saves are folders
   * on shared memory cards, not folders [SaveFolders.forGame] names.
   */
  Ps2,
}

/** Where the emulators that keep a game's save as folders keep each game's. */
object SaveFolders {
  /** The Switch profile in a [SaveArea.folder], which the device's save folders tell. */
  const val PROFILE = "{profile}"

  /** The Switch's user saves of every profile, and the folder of saves every profile shares. */
  const val SWITCH_USERS = "0000000000000000"
  const val SWITCH_DEVICE = "00000000000000000000000000000000"

  // Citra and its forks fill both of the 3DS's ids with zeros.
  private const val CTR_IDS = "00000000000000000000000000000000/00000000000000000000000000000000"

  /**
   * The id [location]'s saves of [platform]'s games are filed under, or null when the launcher
   * doesn't sync that layout by the game's id: it keeps one file per game, every game's saves share
   * a memory card the launcher can't read, or the launcher can't read the id yet.
   */
  fun idKind(location: SaveLocation, platform: PlatformSlug): GameIdKind? {
    if (location.requiresRoot || !location.syncable) return null
    val target = platform.emulatedAs
    return when (location.layout) {
      SaveLayout.SerialPrefixedFolders -> GameIdKind.Psp.takeIf { target == PlatformCatalog.PSP }
      is SaveLayout.Ps2MemoryCards -> GameIdKind.Ps2.takeIf { target == PlatformCatalog.PS2 }
      SaveLayout.ThreeDsTitle -> GameIdKind.N3ds
      SaveLayout.SwitchUserSave -> GameIdKind.Switch
      SaveLayout.GameCubeGci,
      SaveLayout.WiiTitle -> GameIdKind.Disc
      else -> null
    }
  }

  /**
   * The folders that hold the save of the game [id] where [location] keeps it, an id of the kind
   * [idKind] names. Null when [idKind] is null or [GameIdKind.Ps2], or [id] isn't one.
   */
  fun forGame(location: SaveLocation, platform: PlatformSlug, id: String): FolderSave? =
    when (idKind(location, platform)) {
      null,
      GameIdKind.Ps2 -> null
      GameIdKind.Psp ->
        id
          .takeIf { PSP_SERIAL.matches(it) }
          ?.let { FolderSave(listOf(SaveArea("", "", SaveSelection.Prefixed(it))), "$it.zip") }
      GameIdKind.N3ds -> threeDs(id)
      GameIdKind.Switch -> switch(id)
      GameIdKind.Disc ->
        id
          .takeIf { DISC_CODE.matches(it) }
          ?.let { if (location.layout == SaveLayout.WiiTitle) wii(it) else gameCube(it) }
    }

  // Only the title's save data: a title's extdata id is in its exheader, which is encrypted in a
  // retail dump, and a guess could claim another title's. Retail titles only: system titles and
  // homebrew keep their saves elsewhere.
  private fun threeDs(id: String): FolderSave? {
    if (!HEX16.matches(id) || !id.startsWith("0004")) return null
    val high = id.take(8).lowercase()
    val low = id.drop(8).lowercase()
    return FolderSave(
      listOf(SaveArea("$CTR_IDS/title/$high/$low/data", "$high/$low/data/", SaveSelection.Whole())),
      "${id.uppercase()}.zip",
    )
  }

  // The yuzu forks keep the save's size in a file of their own beside it, which isn't the save.
  private val switchSizeFiles =
    setOf(".yuzu_save_size", ".citron_save_size", ".sudachi_save_size", ".suyu_save_size")

  private fun switch(id: String): FolderSave? {
    if (!HEX16.matches(id)) return null
    val title = id.uppercase()
    val except = SaveSelection.Whole(switchSizeFiles)
    return FolderSave(
      listOf(
        SaveArea("$SWITCH_USERS/$PROFILE/$title", "$title/", except),
        SaveArea("$SWITCH_USERS/$SWITCH_DEVICE/$title", "device/$title/", except),
      ),
      "$title.zip",
    )
  }

  private fun gameCube(code: String): FolderSave {
    val region =
      when (code.last()) {
        'E' -> "USA"
        'J',
        'K' -> "JAP"
        else -> "EUR"
      }
    return FolderSave(
      listOf(SaveArea("$region/Card A", "", SaveSelection.GciFiles(code))),
      "$code.zip",
    )
  }

  private fun wii(code: String): FolderSave {
    val hex =
      code.encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return FolderSave(
      listOf(SaveArea("$hex/data", "$hex/data/", SaveSelection.Whole())),
      "$hex.zip",
    )
  }

  private val PSP_SERIAL = Regex("[A-Z]{4}[0-9]{5}")
  private val HEX16 = Regex("[0-9A-Fa-f]{16}")
  private val DISC_CODE = Regex("[A-Z0-9]{4}")
}
