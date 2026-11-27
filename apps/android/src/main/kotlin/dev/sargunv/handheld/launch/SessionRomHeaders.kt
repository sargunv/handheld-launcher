package dev.sargunv.handheld.launch

import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.storage.DiscSets
import dev.sargunv.handheld.session.Platform
import dev.sargunv.handheld.session.romHeaderFields
import dev.sargunv.handheld.ui.game.RomHeaderField
import dev.sargunv.handheld.ui.game.RomHeaders
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads games' headers through the session, which knows each platform's own: of a folder, the file
 * an emulator would open.
 */
object SessionRomHeaders : RomHeaders {
  override suspend fun read(rom: Rom, path: String): List<RomHeaderField> {
    val platform = headerPlatform(PlatformSlug(rom.platformSlug)) ?: return emptyList()
    return withContext(Dispatchers.IO) {
      val file = launchFile(path)?.let(::headerFile) ?: return@withContext emptyList()
      romHeaderFields(platform, file).map { RomHeaderField(it.label, it.value) }
    }
  }

  /** The file that holds the header of the game an emulator opens at [path]. */
  internal fun headerFile(path: String): String? =
    DiscSets.headerFile(path) {
      try {
        File(it).readText()
      } catch (e: IOException) {
        null
      }
    }

  /** The header the games of [slug] carry, or null when the session reads none for it. */
  internal fun headerPlatform(slug: PlatformSlug): Platform? =
    when (PlatformCatalog.emulatedAs(slug)) {
      PlatformCatalog.NES -> Platform.NES
      PlatformCatalog.SNES -> Platform.SNES
      PlatformCatalog.N64 -> Platform.N64
      PlatformCatalog.GB,
      PlatformCatalog.GBC -> Platform.GAME_BOY
      PlatformCatalog.GBA -> Platform.GAME_BOY_ADVANCE
      PlatformCatalog.NDS -> Platform.NINTENDO_DS
      PlatformCatalog.N3DS -> Platform.NINTENDO3DS
      PlatformCatalog.GAMECUBE,
      PlatformCatalog.WII -> Platform.GAME_CUBE
      PlatformCatalog.MASTER_SYSTEM,
      PlatformCatalog.GAME_GEAR -> Platform.MASTER_SYSTEM
      PlatformCatalog.GENESIS,
      PlatformCatalog.SEGA_32X -> Platform.MEGA_DRIVE
      PlatformCatalog.SEGA_CD -> Platform.MEGA_CD
      PlatformCatalog.SATURN -> Platform.SATURN
      PlatformCatalog.DREAMCAST -> Platform.DREAMCAST
      PlatformCatalog.PSX -> Platform.PLAY_STATION
      PlatformCatalog.PS2 -> Platform.PLAY_STATION2
      PlatformCatalog.PSP -> Platform.PSP
      PlatformCatalog.XBOX -> Platform.XBOX
      else -> null
    }
}
