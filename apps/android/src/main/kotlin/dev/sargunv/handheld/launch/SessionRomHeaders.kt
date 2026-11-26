package dev.sargunv.handheld.launch

import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.session.HeaderPlatform
import dev.sargunv.handheld.session.romHeaderFields
import dev.sargunv.handheld.ui.game.RomHeaderField
import dev.sargunv.handheld.ui.game.RomHeaders
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
      val file = launchFile(path) ?: return@withContext emptyList()
      romHeaderFields(platform, file).map { RomHeaderField(it.label, it.value) }
    }
  }

  /** The header the games of [slug] carry, or null when the session reads none for it. */
  internal fun headerPlatform(slug: PlatformSlug): HeaderPlatform? =
    when (PlatformCatalog.emulatedAs(slug)) {
      PlatformCatalog.NES -> HeaderPlatform.NES
      PlatformCatalog.SNES -> HeaderPlatform.SNES
      PlatformCatalog.N64 -> HeaderPlatform.N64
      PlatformCatalog.GB,
      PlatformCatalog.GBC -> HeaderPlatform.GAME_BOY
      PlatformCatalog.GBA -> HeaderPlatform.GAME_BOY_ADVANCE
      PlatformCatalog.NDS -> HeaderPlatform.NINTENDO_DS
      PlatformCatalog.N3DS -> HeaderPlatform.NINTENDO3DS
      PlatformCatalog.GAMECUBE,
      PlatformCatalog.WII -> HeaderPlatform.GAME_CUBE
      PlatformCatalog.MASTER_SYSTEM,
      PlatformCatalog.GAME_GEAR -> HeaderPlatform.MASTER_SYSTEM
      PlatformCatalog.GENESIS,
      PlatformCatalog.SEGA_32X -> HeaderPlatform.MEGA_DRIVE
      PlatformCatalog.SEGA_CD -> HeaderPlatform.MEGA_CD
      PlatformCatalog.SATURN -> HeaderPlatform.SATURN
      PlatformCatalog.DREAMCAST -> HeaderPlatform.DREAMCAST
      PlatformCatalog.PSX -> HeaderPlatform.PLAY_STATION
      PlatformCatalog.PS2 -> HeaderPlatform.PLAY_STATION2
      PlatformCatalog.PSP -> HeaderPlatform.PSP
      PlatformCatalog.XBOX -> HeaderPlatform.XBOX
      else -> null
    }
}
