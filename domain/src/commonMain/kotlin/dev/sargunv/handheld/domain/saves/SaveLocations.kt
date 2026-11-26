// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformCatalog.DREAMCAST
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GAMECUBE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N3DS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NDS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSP
import dev.sargunv.handheld.domain.platform.PlatformCatalog.WII
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * Where an emulator keeps saves, and in what shape.
 *
 * [paths] are candidate folders, most likely first, written with [SavePaths] placeholders.
 * [platforms] limits the location to those platforms' games; null applies it to every platform the
 * emulator runs that has no location of its own. [requiresRoot] marks folders only root can read.
 * [syncable] is false where the layout is known but saves there can't be synced safely yet.
 */
data class SaveLocation(
  val emulator: EmulatorId,
  val paths: List<String>,
  val layout: SaveLayout,
  val platforms: Set<PlatformSlug>? = null,
  val requiresRoot: Boolean = false,
  val syncable: Boolean = true,
)

/** Placeholders in save, state and BIOS paths. */
object SavePaths {
  /** The primary external storage root, such as `/storage/emulated/0`. */
  const val STORAGE = "{storage}"

  /** Each mounted storage volume's root, the primary one and every SD card. */
  const val ANY_STORAGE = "{anyStorage}"

  /** The emulator's installed package, for apps whose forks keep the same layout. */
  const val PACKAGE = "{package}"

  /** RetroArch's folder for the game's core, its `LibretroCore.retroArchFolder`. */
  const val CORE = "{core}"

  val all: Set<String> = setOf(STORAGE, ANY_STORAGE, PACKAGE, CORE)
}

/** Where each known emulator keeps saves. */
object SaveLocations {
  private fun id(value: String) = EmulatorId(value)

  private fun retroArch(id: String, packageName: String): List<SaveLocation> {
    val data = "{storage}/Android/data/$packageName/files/saves"
    return listOf(
      SaveLocation(
        id(id),
        listOf(
          "{storage}/RetroArch/saves/{core}",
          "$data/{core}",
          "/data/data/$packageName/saves/{core}",
          "{storage}/RetroArch/saves",
          data,
          "/data/data/$packageName/saves",
        ),
        SaveLayout.RomNamedFile(listOf("srm", "sav")),
      ),
      SaveLocation(
        id(id),
        listOf("{storage}/RetroArch/saves/dolphin-emu/User/GC", "$data/dolphin-emu/User/GC"),
        SaveLayout.GameCubeGci,
        platforms = setOf(GAMECUBE),
      ),
      SaveLocation(
        id(id),
        listOf(
          "{storage}/RetroArch/saves/dolphin-emu/User/Wii/title/00010000",
          "$data/dolphin-emu/User/Wii/title/00010000",
          "{storage}/RetroArch/saves/User/Wii/title/00010000",
          "$data/User/Wii/title/00010000",
        ),
        SaveLayout.WiiTitle,
        platforms = setOf(WII),
      ),
      SaveLocation(
        id(id),
        listOf("{storage}/RetroArch/saves/PPSSPP/PSP/SAVEDATA", "$data/PPSSPP/PSP/SAVEDATA"),
        SaveLayout.SerialPrefixedFolders,
        platforms = setOf(PSP),
      ),
      SaveLocation(
        id(id),
        listOf(
          "{storage}/RetroArch/saves/Citra/sdmc/Nintendo 3DS",
          "$data/Citra/sdmc/Nintendo 3DS",
        ),
        SaveLayout.ThreeDsTitle,
        platforms = setOf(N3DS),
      ),
    )
  }

  private fun inAppData(id: String, packageName: String, folder: String, layout: SaveLayout) =
    SaveLocation(id(id), listOf(appFiles(packageName, folder)), layout)

  /** [folder] inside [packageName]'s external files folder, or that folder itself when empty. */
  private fun appFiles(packageName: String, folder: String = ""): String =
    "{storage}/Android/data/$packageName/files" + if (folder.isEmpty()) "" else "/$folder"

  private val mupenSaves = SaveLayout.GameDataFolder(listOf("sra", "eep", "fla", "mpk"))

  private val dolphinGc =
    listOf("{storage}/Android/data/{package}/files/GC", "{storage}/dolphin-emu/GC")

  private val dolphinWii =
    listOf(
      "{storage}/Android/data/{package}/files/Wii/title/00010000",
      "{storage}/dolphin-emu/Wii/title/00010000",
    )

  private fun dolphin(id: String) =
    listOf(
      SaveLocation(id(id), dolphinGc, SaveLayout.GameCubeGci, platforms = setOf(GAMECUBE)),
      SaveLocation(id(id), dolphinWii, SaveLayout.WiiTitle, platforms = setOf(WII)),
    )

  private fun switch(id: String, folder: String) =
    inAppData(id, "{package}", folder, SaveLayout.SwitchUserSave)

  private fun pizzaBoy(id: String, packageName: String, sharedFolder: String) =
    SaveLocation(
      id(id),
      listOf("{storage}/$sharedFolder/saves", "{storage}/Android/data/$packageName/files/saves"),
      SaveLayout.RomNamedFile(listOf("sav")),
    )

  private fun ps2(id: String, packageName: String) =
    inAppData(id, packageName, "memcards", SaveLayout.Ps2MemoryCards())

  private fun ppsspp(id: String, packageName: String) =
    SaveLocation(
      id(id),
      listOf("{anyStorage}/PSP/SAVEDATA", "{storage}/Android/data/$packageName/files/PSP/SAVEDATA"),
      SaveLayout.SerialPrefixedFolders,
    )

  private fun vita3k(id: String, packageName: String) =
    inAppData(id, packageName, "VITA/ux0/user/00/savedata", SaveLayout.VitaTitle)

  // The PS3 emulators pin saves to user 00000001.
  private fun ps3(id: String, packageName: String, root: String) =
    inAppData(
      id,
      packageName,
      "$root/dev_hdd0/home/00000001/savedata",
      SaveLayout.SerialPrefixedFolders,
    )

  private fun yabaSanshiro(id: String, packageName: String) =
    SaveLocation(
      id(id),
      listOf(
        "{storage}/Android/data/$packageName/files/yabause/memory",
        "{storage}/yabause/memory",
      ),
      SaveLayout.SharedMemoryCard(listOf("ram")),
      syncable = false,
    )

  val all: List<SaveLocation> =
    retroArch("retroarch", "com.retroarch") +
      retroArch("retroarch_64", "com.retroarch.aarch64") +
      retroArch("retroarch_32", "com.retroarch.ra32") +
      listOf(
        inAppData("mupen64plus_fz", "org.mupen64plusae.v3.fzurita", "GameData", mupenSaves),
        inAppData("mupen64plus_fz_pro", "org.mupen64plusae.v3.fzurita.pro", "GameData", mupenSaves),
        inAppData("m64pro_fzx_plus", "com.m64.fx.plus.emulate", "GameData", mupenSaves),
      ) +
      dolphin("dolphin") +
      dolphin("dolphin_handheld") +
      dolphin("dolphin_cs") +
      listOf(
        inAppData("cemu", "info.cemu.cemu", "mlc01/usr/save/00050000", SaveLayout.WiiUTitle),
        inAppData("citra", "org.citra.citra_emu", "sdmc/Nintendo 3DS", SaveLayout.ThreeDsTitle),
        SaveLocation(
          id("citra_mmj"),
          listOf(
            "{storage}/citra-emu/sdmc/Nintendo 3DS",
            "{storage}/Android/data/org.citra.emu/files/citra-emu/sdmc/Nintendo 3DS",
          ),
          SaveLayout.ThreeDsTitle,
        ),
        SaveLocation(
          id("azahar"),
          listOf(
            "{anyStorage}/Azahar/sdmc/Nintendo 3DS",
            "{storage}/Android/data/{package}/files/sdmc/Nintendo 3DS",
          ),
          SaveLayout.ThreeDsTitle,
        ),
        inAppData("borked3ds", "{package}", "sdmc/Nintendo 3DS", SaveLayout.ThreeDsTitle),
        switch("eden", "nand/user/save"),
        switch("eden_duo", "nand/user/save"),
        switch("lemon", "nand/user/save"),
        switch("citron", "nand/user/save"),
        switch("sudachi", "nand/user/save"),
        switch("yuzu", "nand/user/save"),
        // Ryujinx files a save under the id its save index gives it, not the game's.
        switch("ryujinx", "bis/user/save").copy(syncable = false),
        switch("kenjinx", "bis/user/save").copy(syncable = false),
        switch("strato", "switch/nand/user/save"),
        switch("skyline", "switch/nand/user/save"),
        SaveLocation(
          id("drastic"),
          listOf(
            "{storage}/DraStic/backup",
            "{storage}/Android/data/com.dsemu.drastic/files/backup",
          ),
          SaveLayout.RomNamedFile(listOf("dsv", "sav")),
        ),
        SaveLocation(id("seedlessds"), emptyList(), SaveLayout.BesideRom(listOf("dsv", "sav"))),
        SaveLocation(
          id("pokedaisy"),
          emptyList(),
          SaveLayout.BesideRom(listOf("sav", "srm")),
          platforms = setOf(NDS),
        ),
        inAppData(
          "pokedaisy",
          "com.pokedaisy.app",
          "saves",
          SaveLayout.RomNamedFile(listOf("sav", "srm")),
        ),
        SaveLocation(
          id("melonds"),
          listOf("{storage}/Android/data/{package}/files/saves", "{storage}/melonDS/saves"),
          SaveLayout.RomNamedFile(listOf("sav")),
        ),
        SaveLocation(
          id("melondualds"),
          listOf("{storage}/Android/data/{package}/files/saves", "{storage}/melonDS/saves"),
          SaveLayout.RomNamedFile(listOf("sav")),
        ),
        inAppData(
          "pizza_boy_ds",
          "com.pizzaemulators.pbds",
          "save",
          SaveLayout.RomNamedFile(listOf("sav")),
        ),
        pizzaBoy("pizza_boy_gba", "it.dbtecno.pizzaboygba", "PizzaBoyGBA"),
        pizzaBoy("pizza_boy_gba_pro", "it.dbtecno.pizzaboygbapro", "PizzaBoyGBA"),
        pizzaBoy("pizza_boy_gb", "it.dbtecno.pizzaboy", "PizzaBoy"),
        pizzaBoy("pizza_boy_gb_pro", "it.dbtecno.pizzaboypro", "PizzaBoy"),
        inAppData(
            "linkboy",
            "com.pixelrespawn.linkboy",
            "saves",
            SaveLayout.RomNamedFile(listOf("sav")),
          )
          .copy(syncable = false),
        SaveLocation(
          id("duckstation"),
          listOf(
            "{storage}/Android/data/com.github.stenzek.duckstation/files/memcards",
            "{storage}/duckstation/memcards",
          ),
          SaveLayout.PsxMemoryCards,
          requiresRoot = true,
        ),
        SaveLocation(
          id("armsx1"),
          listOf(
            "{storage}/Android/data/com.nanodata.armsx/files/swanstation/cards",
            "{storage}/Android/data/com.nanodata.armsx/files/psxlab/cards",
          ),
          SaveLayout.PsxMemoryCards,
          syncable = false,
        ),
        ps2("nethersx2", "{package}"),
        ps2("armsx2", "com.armsx2"),
        ps2("armsx2_play", "come.nanodata.armsx2"),
        ps2("pcsx2", "net.pcsx2.emulator"),
        inAppData(
          "pocketgoal",
          "dev.sargunv.pocketgoal",
          "memcards",
          SaveLayout.Ps2MemoryCards(folderCards = true),
        ),
        inAppData(
            "psx2",
            "com.izzy2lost.psx2",
            "memcards",
            SaveLayout.SharedMemoryCard(listOf("ps2")),
          )
          .copy(syncable = false),
        ppsspp("ppsspp", "org.ppsspp.ppsspp"),
        ppsspp("ppsspp_gold", "org.ppsspp.ppssppgold"),
        vita3k("vita3k", "org.vita3k.emulator"),
        vita3k("vita3k_zx", "org.vita3k.emulator.ikhoeyZX"),
        vita3k("vita3k_plus", "org.vita3kplus.emulator"),
        ps3("aps3e", "aenu.aps3e", "aps3e/config"),
        ps3("armsx3", "com.armsx3", "config"),
        ps3("armsx3_play", "com.armsx3.play", "config"),
        SaveLocation(
          id("hakux"),
          listOf("/data/data/com.rfandango.haku_x/files/native/hdd/Partition1/UDATA"),
          SaveLayout.XboxTitle,
          requiresRoot = true,
        ),
        inAppData(
            "redream",
            "io.recompiled.redream",
            "",
            SaveLayout.SharedMemoryCard(listOf("bin")),
          )
          .copy(syncable = false),
        SaveLocation(
          id("flycast"),
          listOf(
            "{storage}/Android/data/com.flycast.emulator/files/data",
            "{storage}/Flycast/data",
          ),
          SaveLayout.DreamcastVmu,
          platforms = setOf(DREAMCAST),
        ),
        inAppData(
            "saturn_emu",
            "com.explusalpha.SaturnEmu",
            "",
            SaveLayout.RomNamedFile(listOf("srm", "sav")),
          )
          .copy(syncable = false),
        yabaSanshiro("yabasanshiro", "org.devmiyax.yabasanshioro2"),
        yabaSanshiro("yabasanshiro_pro", "org.devmiyax.yabasanshioro2.pro"),
        inAppData(
          "md_emu",
          "com.explusalpha.MdEmu",
          "",
          SaveLayout.RomNamedFile(listOf("srm", "sav")),
        ),
        inAppData(
            "mame4droid",
            "com.seleuco.mame4droid",
            "nvram",
            SaveLayout.RomNamedFile(listOf("nv")),
          )
          .copy(syncable = false),
        SaveLocation(
          id("phobos"),
          listOf("/data/data/com.phobos.emulator/files/saves"),
          SaveLayout.RomNamedFile(
            listOf("ram", "srm", "eeprom", "card", "sav", "fla", "flash", "rtc", "pak"),
          ),
          requiresRoot = true,
          syncable = false,
        ),
      )

  /** Known emulators whose save locations nobody has mapped yet. */
  val unmapped: Set<EmulatorId> = setOf(id("ppsspp_legacy"), id("super_zsnes"), id("fbalpha"))

  /**
   * Where [emulator] keeps [platform]'s saves: its location for that platform, else its location
   * for every platform; null when neither is known.
   */
  /**
   * The emulators that keep PS2 saves on PS2 memory cards. A game's saves sync from any of them as
   * the same per-game card, so one's save is another's.
   */
  val ps2Cards: Set<EmulatorId> =
    all.filter { it.layout is SaveLayout.Ps2MemoryCards }.map { it.emulator }.toSet()

  fun forEmulator(emulator: EmulatorId, platform: PlatformSlug): SaveLocation? {
    val target = PlatformCatalog.emulatedAs(platform)
    val own = all.filter { it.emulator == emulator }
    return own.firstOrNull { it.platforms?.contains(target) == true }
      ?: own.firstOrNull { it.platforms == null }
  }
}
