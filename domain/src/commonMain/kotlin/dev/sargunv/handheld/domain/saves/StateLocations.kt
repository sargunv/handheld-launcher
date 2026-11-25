// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId

/**
 * How an emulator names a game's save state files. The auto slot is the state an emulator writes on
 * exit and resumes from.
 */
sealed interface StateNaming {
  /**
   * Named after the ROM: slot 0 is `<rom>.<extension>`, slot n is `<rom>.<extension><n>`, and the
   * auto slot is `<rom>.<extension>.<autoSuffix>` when there is one.
   */
  data class RomAndSuffix(val extension: String, val autoSuffix: String? = null) : StateNaming

  /** Named after the ROM: slot n is `<rom><separator><n>.<extension>`. */
  data class RomAndSlot(val separator: String, val extension: String) : StateNaming

  /**
   * DuckStation's states, named after the disc serial: `<serial>_<n>.sav`, with the auto slot as
   * `<serial>_resume.sav`. States named `savestate_<n>.sav` or `resume.sav` belong to no game.
   */
  data object DuckStationSerial : StateNaming

  /**
   * PPSSPP's states, named after the disc: `<disc id>_<disc version>_<n>.ppst`, with a `.jpg`
   * screenshot of the same name. Undo states never match.
   */
  data object PpssppSerial : StateNaming

  /** Dolphin's states, named after the game id: `<game id>.s<NN>`, slots from `01`. */
  data object DolphinGameId : StateNaming
}

/**
 * Where an emulator keeps save states, and how it names them.
 *
 * [paths] are candidate folders, most likely first, written with [SavePaths] placeholders.
 * [maxSlots] is null for emulators with no slot limit. [syncable] is false where states there can't
 * be synced safely yet.
 */
data class StateLocation(
  val emulator: EmulatorId,
  val paths: List<String>,
  val naming: StateNaming,
  val maxSlots: Int? = 10,
  val syncable: Boolean = true,
)

/** Where each known emulator keeps save states. */
object StateLocations {
  private fun id(value: String) = EmulatorId(value)

  private fun retroArch(id: String, packageName: String) =
    StateLocation(
      id(id),
      listOf(
        "{storage}/RetroArch/states/{core}",
        "{storage}/Android/data/$packageName/files/states/{core}",
        "/data/data/$packageName/states/{core}",
        "{storage}/RetroArch/states",
        "{storage}/Android/data/$packageName/files/states",
        "/data/data/$packageName/states",
      ),
      StateNaming.RomAndSuffix("state", autoSuffix = "auto"),
    )

  private fun ppsspp(id: String, packageName: String) =
    StateLocation(
      id(id),
      listOf(
        "{storage}/PSP/PPSSPP_STATE",
        "{storage}/Android/data/$packageName/files/PSP/PPSSPP_STATE",
      ),
      StateNaming.PpssppSerial,
      maxSlots = 5,
    )

  private fun melonDs(id: String, packageName: String) =
    StateLocation(
      id(id),
      listOf("{storage}/melonDS/states", "{storage}/Android/data/$packageName/files/states"),
      StateNaming.RomAndSlot(".", "mln"),
      maxSlots = 8,
    )

  private fun mupen(id: String, packageName: String) =
    StateLocation(
      id(id),
      listOf("{storage}/Android/data/$packageName/files/GameData"),
      StateNaming.RomAndSuffix("st"),
      syncable = false,
    )

  private fun pizzaBoy(id: String, packageName: String, sharedFolder: String) =
    StateLocation(
      id(id),
      listOf("{storage}/$sharedFolder/states", "{storage}/Android/data/$packageName/files/states"),
      StateNaming.RomAndSlot("_", "state"),
    )

  val all: List<StateLocation> =
    listOf(
      retroArch("retroarch", "com.retroarch"),
      retroArch("retroarch_64", "com.retroarch.aarch64"),
      retroArch("retroarch_32", "com.retroarch.ra32"),
      ppsspp("ppsspp", "org.ppsspp.ppsspp"),
      ppsspp("ppsspp_gold", "org.ppsspp.ppssppgold"),
      ppsspp("ppsspp_legacy", "org.ppsspp.ppsspplegacy"),
      StateLocation(
        id("drastic"),
        listOf(
          "{storage}/DraStic/savestates",
          "{storage}/Android/data/com.dsemu.drastic/files/savestates",
        ),
        StateNaming.RomAndSlot("_", "dss"),
        maxSlots = null,
      ),
      melonDs("melonds", "me.magnum.melonds"),
      melonDs("melondualds", "me.magnum.melondualds"),
      StateLocation(
        id("duckstation"),
        listOf(
          "{storage}/duckstation/savestates",
          "{storage}/Android/data/com.github.stenzek.duckstation/files/savestates",
        ),
        StateNaming.DuckStationSerial,
      ),
      StateLocation(
        id("dolphin"),
        listOf(
          "{storage}/Android/data/{package}/files/StateSaves",
          "{storage}/dolphin-emu/StateSaves",
        ),
        StateNaming.DolphinGameId,
        syncable = false,
      ),
      mupen("mupen64plus_fz", "org.mupen64plusae.v3.fzurita"),
      mupen("mupen64plus_fz_pro", "org.mupen64plusae.v3.fzurita.pro"),
      mupen("m64pro_fzx_plus", "com.m64.fx.plus.emulate"),
      pizzaBoy("pizza_boy_gba", "it.dbtecno.pizzaboygba", "PizzaBoyGBA"),
      pizzaBoy("pizza_boy_gba_pro", "it.dbtecno.pizzaboygbapro", "PizzaBoyGBA"),
      pizzaBoy("pizza_boy_gb", "it.dbtecno.pizzaboy", "PizzaBoy"),
      pizzaBoy("pizza_boy_gb_pro", "it.dbtecno.pizzaboypro", "PizzaBoy"),
    )

  /** Where [emulator] keeps save states, or null when it isn't known. */
  fun forEmulator(emulator: EmulatorId): StateLocation? = all.firstOrNull {
    it.emulator == emulator
  }
}
