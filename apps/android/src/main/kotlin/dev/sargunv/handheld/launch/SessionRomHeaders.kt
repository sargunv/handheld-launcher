package dev.sargunv.handheld.launch

import dev.sargunv.handheld.data.downloads.headerFile
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.session.HeaderValue
import dev.sargunv.handheld.session.Platform
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
    val platform = rom.platformSlug.headerPlatform() ?: return emptyList()
    return withContext(Dispatchers.IO) {
      val file = launchFile(path)?.let(::headerFile) ?: return@withContext emptyList()
      romHeaderFields(platform, file).map {
        RomHeaderField(RomHeaderField.Key.valueOf(it.key.name), it.value.toUi())
      }
    }
  }
}

private fun HeaderValue.toUi(): RomHeaderField.Value =
  when (this) {
    is HeaderValue.Text -> RomHeaderField.Value.Text(text)
    is HeaderValue.Names -> RomHeaderField.Value.Names(names)
    is HeaderValue.Number -> RomHeaderField.Value.Number(number.toLong())
    is HeaderValue.Size -> RomHeaderField.Value.Size(bytes.toLong())
    is HeaderValue.Flag -> RomHeaderField.Value.Flag(set)
    is HeaderValue.Hex -> RomHeaderField.Value.Hex(number, digits.toInt())
    is HeaderValue.Date -> RomHeaderField.Value.Date(year.toInt(), month.toInt(), day.toInt())
  }

/** The header this platform's games carry, or null when the session reads none for it. */
internal fun PlatformSlug.headerPlatform(): Platform? =
  when (emulatedAs) {
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
    PlatformCatalog.SWITCH -> Platform.SWITCH
    else -> null
  }
