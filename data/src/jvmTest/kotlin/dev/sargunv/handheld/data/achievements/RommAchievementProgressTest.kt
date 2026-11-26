package dev.sargunv.handheld.data.achievements

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RommAchievementProgressTest {
  /** The user as RomM sends it once they've linked RetroAchievements and RomM has fetched it. */
  private val linked =
    Fixtures.me.replace(
      "\"current_device_id\": null,",
      """
      "current_device_id": null, "ra_username": "Player",
      "ra_progression": {"total": 2, "results": [
        {"rom_ra_id": 504, "max_possible": 40, "num_awarded": 12, "num_awarded_hardcore": 3,
         "most_recent_awarded_date": "2025-01-01 00:00:00", "earned_achievements": []},
        {"rom_ra_id": 7, "max_possible": 10, "num_awarded": 10, "num_awarded_hardcore": 10,
         "most_recent_awarded_date": null, "earned_achievements": []}
      ]},
      """,
    )

  /** The user as RomM sends it when they haven't linked RetroAchievements. */
  private val unlinked =
    Fixtures.me.replace(
      "\"current_device_id\": null,",
      "\"current_device_id\": null, \"ra_username\": \"\", \"ra_progression\": {},",
    )

  private fun rom(id: Long, raId: Long?, achievements: Int) =
    Fixtures.rom(id)
      .replace(
        "\"igdb_id\": null,",
        """"igdb_id": null, "ra_id": ${raId ?: "null"},
        "merged_ra_metadata": {"first_release_date": null, "genres": [], "companies": [],
          "achievements": [${(1..achievements).joinToString { """{"ra_id": $it, "title": "A$it"}""" }}]},""",
      )

  private fun server(user: String, rom: String) = MockServer { request ->
    when (request.apiPath) {
      "users/me" -> json(user)
      "roms/5" -> json(rom)
      else -> error("unexpected ${request.apiPath}")
    }
  }

  @Test
  fun readsTheProgressRommSynced() = runTest {
    val server = server(linked, rom(5, raId = 504, achievements = 40))
    assertEquals(
      RommAchievementProgress(unlocked = 12, unlockedHardcore = 3, total = 40),
      server.client.achievementProgress(5),
    )
    assertTrue(server.requests.all { it.method == HttpMethod.Get })
  }

  @Test
  fun aGameNotYetPlayedHasNoneEarned() = runTest {
    val server = server(linked, rom(5, raId = 99, achievements = 25))
    assertEquals(RommAchievementProgress(0, 0, 25), server.client.achievementProgress(5))
  }

  @Test
  fun nothingWithoutALinkedAccount() = runTest {
    val server = server(unlinked, rom(5, raId = 504, achievements = 40))
    assertNull(server.client.achievementProgress(5))
    // Only the user is read.
    assertEquals(listOf("users/me"), server.requests.map { it.apiPath })
  }

  @Test
  fun nothingForAGameWithoutASet() = runTest {
    assertNull(server(linked, rom(5, raId = null, achievements = 0)).client.achievementProgress(5))
    assertNull(server(linked, rom(5, raId = 99, achievements = 0)).client.achievementProgress(5))
  }
}
