package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.httpClient
import dev.sargunv.handheld.data.romm.RommClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Syncs a real server's library into a fresh cache, reading only. It runs when `ROMM_URL` and
 * `ROMM_TOKEN` are set, and prints counts but no library contents. `mise run test:live` sets them
 * from fnox.
 */
class LiveLibrarySyncTest {
  @Test
  fun syncsTheLibrary() = runBlocking {
    val url = System.getenv("ROMM_URL")
    val token = System.getenv("ROMM_TOKEN")
    assumeTrue("ROMM_URL and ROMM_TOKEN aren't set", !url.isNullOrBlank() && !token.isNullOrBlank())

    val dir = Files.createTempDirectory("live-library-sync").toFile()
    try {
      LibraryCache.open(dir.resolve("library.db").path).use { cache ->
        httpClient().use { check(RommClient(url, token, it), cache) }
      }
    } finally {
      check(dir.deleteRecursively())
    }
  }

  private suspend fun check(client: RommClient, cache: LibraryCache) {
    val sync = LibrarySync(client, cache, client.me().id)
    sync.sync()
    val romCount = cache.romCount()
    assertEquals(client.romIds().size.toLong(), romCount)

    val platforms = cache.platforms().first()
    val roms = platforms.flatMap { cache.romsOnPlatform(it.id).first() }
    assertEquals(romCount, roms.size.toLong(), "Every ROM's platform is cached")
    assertEquals(roms.size, roms.map { it.id }.toSet().size)
    for (rom in roms) {
      assertTrue(rom.title.isNotBlank(), "ROM ${rom.id} has a title")
      assertTrue(rom.sizeBytes >= 0 && rom.files.all { it.sizeBytes >= 0 })
      assertEquals(rom.sizeBytes, rom.files.sumOf { it.sizeBytes }, "ROM ${rom.id}'s files add up")
    }

    sync.sync()
    assertEquals(romCount, cache.romCount())

    println(
      "Live sync: ${platforms.size} platforms, $romCount ROMs with " +
        "${roms.sumOf { it.files.size }} files (${roms.count { it.files.size > 1 }} with several), " +
        "${cache.collections().first().size} collections",
    )
  }
}
