package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.romm.RommDeviceSync
import dev.sargunv.handheld.data.romm.RommSave
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class GameModelTest {
  private val rom =
    Rom(
      id = 1,
      platformId = 1,
      platformSlug = PlatformSlug("gba"),
      title = "Game",
      sortKey = "game",
      fileName = "Game.gba",
      sizeBytes = 4_200_000,
      summary = null,
      genres = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      files = emptyList(),
      addedAt = Instant.fromEpochSeconds(0),
    )

  @Test
  fun thePrimaryActionFollowsTheGamesState() {
    assertNull(primaryAction(rom, null, playing = false))
    assertEquals(
      "Download (4 MiB)",
      primaryAction(rom, RomStatus.NotOnDevice, playing = false)?.label,
    )
    assertEquals(
      PrimaryAction.Waiting("Downloading 40%"),
      primaryAction(rom, RomStatus.Downloading(1_680_000, 4_200_000), playing = false),
    )
    assertEquals(PrimaryAction.Retry, primaryAction(rom, RomStatus.Failed("x"), playing = false))
    assertEquals(
      PrimaryAction.Play("/ROMs/gba/Game.gba"),
      primaryAction(rom, RomStatus.OnDevice("/ROMs/gba/Game.gba"), playing = false),
    )
    assertEquals(
      PrimaryAction.Waiting("Playing"),
      primaryAction(rom, RomStatus.OnDevice("/ROMs/gba/Game.gba"), playing = true),
    )
    assertEquals(
      PrimaryAction.Waiting("Downloading core 40%"),
      primaryAction(
        rom,
        RomStatus.OnDevice("/ROMs/gba/Game.gba"),
        playing = true,
        progress = "Downloading core 40%",
      ),
    )
  }

  private fun save(id: Long, day: Int, origin: String?, syncs: List<RommDeviceSync> = emptyList()) =
    RommSave(
      id = id,
      fileName = "Game.sav",
      fileSizeBytes = 8192,
      emulator = "mgba",
      originDeviceId = origin,
      deviceSyncs = syncs,
      updatedAt = Instant.parse("2025-01-0${day}T00:00:00Z"),
    )

  @Test
  fun theSaveHistoryNamesEachSavesDeviceNewestFirst() {
    val deck = RommDeviceSync("deck", "Steam Deck")
    assertEquals(
      listOf(
        ServerSave(THIS_DEVICE, "mgba", Instant.parse("2025-01-03T00:00:00Z"), 8192),
        ServerSave("Steam Deck", "mgba", Instant.parse("2025-01-02T00:00:00Z"), 8192),
        ServerSave(null, "mgba", Instant.parse("2025-01-01T00:00:00Z"), 8192),
      ),
      serverSaveHistory(
        listOf(save(1, 1, null), save(2, 2, "deck", listOf(deck)), save(3, 3, "thor")),
        thisDevice = "thor",
      ),
    )
  }
}
