// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.saves

/**
 * How an emulator lays a game's save out under one of its save folders. Each shape needs its own
 * handling to find, upload and restore a save without touching another game's.
 */
sealed interface SaveLayout {
  /** One file per game, named after the ROM: `<rom name>.<extension>`. */
  data class RomNamedFile(val extensions: List<String>) : SaveLayout

  /** One file per game beside the ROM itself, named after it. There's no save folder. */
  data class BesideRom(val extensions: List<String>) : SaveLayout

  /** A folder per game that the emulator names from the ROM header, holding its save files. */
  data class GameDataFolder(val extensions: List<String>) : SaveLayout

  /**
   * One memory card image that every game writes to, such as a PS2 file card or a Saturn's backup
   * RAM. A game's save is an entry inside it, so syncing it means syncing every game's.
   */
  data class SharedMemoryCard(val extensions: List<String>) : SaveLayout

  /**
   * DuckStation's PlayStation memory cards: a `.mcd` image per game, named after the game, with
   * slot 1 ending `_1.mcd`. The saves inside carry the disc serial, which tells the cards apart.
   */
  data object PsxMemoryCards : SaveLayout

  /**
   * PCSX2-family PlayStation 2 memory cards: `.ps2` cards in the folder, each either a file image
   * or, in folder mode, a directory holding a subfolder per save, named after the game's serial
   * behind a region prefix (`BA`, `BE` or `BI`). The emulator's `PCSX2-Android.ini` names the
   * folder and slot 1's card. Every game shares the cards, so a game's save is its folders on them.
   *
   * [folderCards] makes a card the launcher creates a folder card, for an app that reads only
   * those, such as PocketGOAL; otherwise it's an 8 MB file card, as PCSX2 formats one.
   */
  data class Ps2MemoryCards(val folderCards: Boolean = false) : SaveLayout

  /**
   * GameCube saves as GCI files: `<region>/Card A/<maker>-<game id>-<name>.gci`, where the region
   * is `USA`, `EUR` or `JAP` from the game id's last letter (`P` for Europe, `J` or `K` for Japan).
   * A game can own several files.
   */
  data object GameCubeGci : SaveLayout

  /** Wii NAND saves: `<game id as hex>/data`, under the `title/00010000` folder. */
  data object WiiTitle : SaveLayout

  /**
   * Wii U saves: `<title id low>/user/<account id>` and `<title id low>/common`, under the
   * `usr/save/00050000` folder.
   */
  data object WiiUTitle : SaveLayout

  /**
   * Switch saves: `0000000000000000/<profile id>/<title id>`, where the profile id is 16 or 32 hex
   * digits and the title id is 16 hex digits beginning `01`.
   */
  data object SwitchUserSave : SaveLayout

  /**
   * 3DS saves: `<id0>/<id1>/title/00040000/<title id low>/data`, with extra data under
   * `<id0>/<id1>/extdata`.
   */
  data object ThreeDsTitle : SaveLayout

  /**
   * Folders that share the game's serial as a prefix, such as PSP's `ULUS10064DATA00` beside
   * `ULUS10064SETTINGS`, or PS3's `BCUS99086GAMEDATA` beside `BCUS99086-AUTOSAVE`. A game's save is
   * every folder with its prefix, not one folder.
   */
  data object SerialPrefixedFolders : SaveLayout

  /** PlayStation Vita saves: a folder per game named after its title id, such as `PCSE00082`. */
  data object VitaTitle : SaveLayout

  /** Xbox saves: a folder per game named after its title id in 8 hex digits, under `UDATA`. */
  data object XboxTitle : SaveLayout

  /**
   * Flycast's Dreamcast VMU images. With per-game VMUs on, port A1's card is `<product
   * number>_vmu_save_A1.bin`, the product number being the disc's IP.BIN id; otherwise every game
   * shares `vmu_save_A1.bin`.
   */
  data object DreamcastVmu : SaveLayout
}
