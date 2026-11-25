package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.romm.RommClient
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Syncs a real server's library into a fresh cache, reading only. It runs when `ROMM_URL` and
 * `ROMM_TOKEN` are set, and prints counts but no library contents:
 * ```sh
 * ROMM_URL=... ROMM_TOKEN=... ./gradlew :data:jvmTest --tests '*LiveLibrarySyncTest' --rerun
 * ```
 */
class LiveLibrarySyncTest {
  @Test
  fun syncsTheLibrary() = runBlocking {
    val url = System.getenv("ROMM_URL")
    val token = System.getenv("ROMM_TOKEN")
    assumeTrue("ROMM_URL and ROMM_TOKEN aren't set", !url.isNullOrBlank() && !token.isNullOrBlank())

    val dir = Files.createTempDirectory("live-library-sync")
    try {
      LibraryCache.open(dir.resolve("library.db").toString()).use { cache ->
        RommClient(url, token).use { client -> check(client, cache) }
      }
    } finally {
      @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
    }
  }

  private suspend fun check(client: RommClient, cache: LibraryCache) {
    val sync = LibrarySync(client, cache)
    val first = sync.sync()
    assertFalse(first.incremental)
    assertEquals(client.romIds().size.toLong(), first.roms)
    assertEquals(first.roms, first.romsFetched.toLong())

    val platforms = cache.platforms().first()
    assertEquals(first.platforms, platforms.size)
    val roms = platforms.flatMap { cache.romsOnPlatform(it.id).first() }
    assertEquals(first.roms, roms.size.toLong(), "Every ROM's platform is cached")
    assertEquals(roms.size, roms.map { it.id }.toSet().size)
    for (rom in roms) {
      assertTrue(rom.title.isNotBlank(), "ROM ${rom.id} has a title")
      assertTrue(rom.sizeBytes >= 0 && rom.files.all { it.sizeBytes >= 0 })
      assertEquals(rom.sizeBytes, rom.files.sumOf { it.sizeBytes }, "ROM ${rom.id}'s files add up")
    }
    for (rom in roms.take(25)) {
      if (ftsQuery(rom.title) == null) continue
      assertTrue(
        cache.search(rom.title, limit = 1000).any { it.id == rom.id },
        "ROM ${rom.id} is found by its title",
      )
    }

    val again = sync.sync()
    assertTrue(again.incremental)
    assertEquals(first.roms, again.roms)
    assertTrue(again.romsFetched < first.romsFetched || first.romsFetched == 0)

    println(
      "Live sync: ${first.platforms} platforms, ${first.roms} ROMs with " +
        "${roms.sumOf { it.files.size }} files (${roms.count { it.hasMultipleFiles }} with several), " +
        "${first.collections} collections; the incremental re-sync fetched ${again.romsFetched} ROMs " +
        "and removed ${again.romsRemoved}",
    )
  }
}
