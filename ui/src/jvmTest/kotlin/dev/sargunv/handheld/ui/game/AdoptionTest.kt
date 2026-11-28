package dev.sargunv.handheld.ui.game

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.lastPlayed
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * What the app keeps for a local game goes to the server's game once the file is that game's: the
 * device has Homebrew.gb, which the server gains as Homebrew (World).
 */
@OptIn(ExperimentalTestApi::class)
class AdoptionTest {
  private val storage = createTempDirectory("adoption-storage").toFile()
  private val homebrew = File(storage, "ROMs/gb/Homebrew.gb")
  private val library = LibraryCache.open(path = null)
  private val local = GameKey.Local(homebrew.path)
  private val rom = GameKey.Romm(2)
  private val choice = LaunchChoice("com.example.emulator")

  @Volatile private var serverHasHomebrew = false

  /** The settings as the app stored them, in order. */
  private val saved = Collections.synchronizedList(mutableListOf<Settings>())

  /** What the platform's per-game state was asked to adopt. */
  private val adopted = Collections.synchronizedList(mutableListOf<Adoption>())

  private lateinit var data: HandheldData

  init {
    homebrew.parentFile.mkdirs()
    homebrew.writeText("homebrew")
  }

  @AfterTest
  fun cleanUp() {
    library.close()
    check(storage.deleteRecursively())
  }

  private val server = fakeRomm { path, request ->
    json(
      when (path) {
        "platforms" -> "[${platform()}]"
        "roms" ->
          if (request.lastPlayed) page(emptyList())
          else page(listOfNotNull(HomebrewRom.takeIf { serverHasHomebrew }))
        "roms/identifiers" -> if (serverHasHomebrew) "[2]" else "[]"
        "collections",
        "collections/smart" -> "[]"
        "saves" -> "[]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  /** Opens Homebrew's page, with an Open with choice for its file. */
  private fun ComposeUiTest.openHomebrew(launcher: GameLauncher = FakeLauncher()) {
    data =
      launchApp(
        server,
        library = library,
        storage = listOf(StorageTarget(storage.path, "Test storage")),
        settings = Settings(launch = LaunchRules(gameOverrides = mapOf(local to choice))),
        saveSettings = { saved += it },
        launcher = launcher,
        gameState =
          PerGameState {
            adopted += it
            emptyList()
          },
        open = OpenTarget.Local(homebrew.path),
      )
    awaitText("Play")
  }

  private fun ComposeUiTest.serverGainsHomebrew() {
    serverHasHomebrew = true
    data.romm.syncNow()
    waitUntil(timeoutMillis = 5_000) {
      runBlocking { data.downloads.statuses().first() }[rom.romId] is RomStatus.OnDevice
    }
  }

  @Test
  fun aLocalGamesStateGoesToTheServersGameInOneStep() = runComposeUiTest {
    openHomebrew()
    serverGainsHomebrew()
    awaitText("Homebrew (World)")
    waitUntil(timeoutMillis = 5_000) { Adoption(local, rom) in adopted }
    waitUntil(timeoutMillis = 5_000) {
      saved.lastOrNull()?.launch?.gameOverrides == mapOf(rom to choice)
    }
  }

  @Test
  fun aGameThatsPlayingKeepsItsStateWhereItIsUntilItsOver() = runComposeUiTest {
    val hold = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(hold = hold)
    openHomebrew(launcher)
    press(Key.ButtonA)
    runBlocking { launcher.started.await() }
    serverGainsHomebrew()
    waitForIdle()
    assertTrue(adopted.none { it.local == local }, "$adopted")
    assertTrue(saved.none { rom in it.launch.gameOverrides }, "$saved")

    hold.complete(Unit)
    waitUntil(timeoutMillis = 5_000) { Adoption(local, rom) in adopted }
    waitUntil(timeoutMillis = 5_000) {
      saved.lastOrNull()?.launch?.gameOverrides == mapOf(rom to choice)
    }
    assertEquals(choice, saved.last().launch.gameOverrides[rom])
  }

  @Test
  fun aGameWhoseSaveIsStillSyncingKeepsItsStateAfterItsScreenGoesAway() = runComposeUiTest {
    val afterGame = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(afterGame = afterGame)
    openHomebrew(launcher)
    press(Key.ButtonA)
    waitUntil(timeoutMillis = 5_000) {
      launcher.playing.value == null && local in launcher.unfinished.value
    }
    press(Key.ButtonB)
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithTag("primary").fetchSemanticsNodes().isEmpty()
    }
    serverGainsHomebrew()
    waitForIdle()
    assertTrue(adopted.none { it.local == local }, "$adopted")

    afterGame.complete(Unit)
    waitUntil(timeoutMillis = 5_000) { Adoption(local, rom) in adopted }
  }

  @Test
  fun aRomWhoseFileStillHasLocalSavesPlaysWithThem() = runComposeUiTest {
    serverHasHomebrew = true
    // The saves kept per game, whose adoption waits until the test lets it.
    val saves = ConcurrentHashMap(mapOf<GameKey, String>(local to "local save"))
    val letAdopt = CompletableDeferred<Unit>()
    val playedWith = CompletableDeferred<String?>()
    launchApp(
      server,
      library = library,
      storage = listOf(StorageTarget(storage.path, "Test storage")),
      launcher = FakeLauncher(onPlay = { playedWith.complete(saves[rom]) }),
      gameState =
        PerGameState { adoptions ->
          if (adoptions.isNotEmpty()) letAdopt.await()
          for (adoption in adoptions) {
            saves.remove(adoption.local)?.let { saves[adoption.rom] = it }
          }
          emptyList()
        },
      open = OpenTarget.Game(rom.romId),
    )
    awaitText("Play")
    press(Key.ButtonA)
    waitForIdle()
    letAdopt.complete(Unit)
    awaitText("Played")
    assertEquals("local save", runBlocking { playedWith.await() })
  }

  @Test
  fun anAdoptionThatCouldntFinishIsTriedAgainWhenTheRomPlays() = runComposeUiTest {
    serverHasHomebrew = true
    val saves = ConcurrentHashMap(mapOf<GameKey, String>(local to "local save"))
    // The first try fails, as when the save folder can't move.
    val tries = AtomicInteger()
    val playedWith = CompletableDeferred<String?>()
    launchApp(
      server,
      library = library,
      storage = listOf(StorageTarget(storage.path, "Test storage")),
      launcher = FakeLauncher(onPlay = { playedWith.complete(saves[rom]) }),
      gameState =
        PerGameState { adoptions ->
          if (adoptions.isNotEmpty() && tries.getAndIncrement() == 0) return@PerGameState adoptions
          for (adoption in adoptions) {
            saves.remove(adoption.local)?.let { saves[adoption.rom] = it }
          }
          emptyList()
        },
      open = OpenTarget.Game(rom.romId),
    )
    awaitText("Play")
    waitUntil(timeoutMillis = 5_000) { tries.get() > 0 }
    press(Key.ButtonA)
    awaitText("Played")
    assertEquals("local save", runBlocking { playedWith.await() })
  }

  private companion object {
    val HomebrewRom =
      rom(2, "Homebrew (World)", file = "Homebrew.gb", size = 8, added = "2024-01-02")
  }
}
