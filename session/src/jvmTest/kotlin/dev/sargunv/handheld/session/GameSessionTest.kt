package dev.sargunv.handheld.session

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the Kotlin bindings add to the session, against the cores that `mise run session:fixtures`
 * builds. The Rust tests cover the session itself.
 */
class GameSessionTest {
  private val dir = Files.createTempDirectory("session-test").toFile()
  private val sessions = mutableListOf<GameSession>()

  @AfterTest
  fun cleanUp() {
    sessions.forEach { it.close() }
    dir.deleteRecursively()
  }

  private fun fixture(name: String): File {
    val root =
      System.getenv("HANDHELD_SESSION_FIXTURES")?.let(::File)
        ?: File(
          System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
          "handheld/session-fixtures",
        )
    val file = root.resolve(name)
    check(file.exists()) {
      "missing test fixture $file; run `mise run session:fixtures` to build it"
    }
    return file
  }

  private fun options(corePath: String) =
    SessionOptions(
      corePath = corePath,
      contentPath = null,
      systemDir = dir.resolve("system").path,
      saveDir = dir.resolve("saves").path,
      saveRamPath = null,
      coreOptions = emptyMap(),
      video = VideoMode.OFFSCREEN,
    )

  private fun start(core: String, listener: SessionListener? = null): GameSession =
    GameSession.start(options(fixture(core).path), listener).also { sessions += it }

  @Test
  fun startsAndReturnsFrames() {
    val session = start("test_libretro.so")
    session.advance(1u)
    val frame = assertNotNull(session.captureFrame())
    assertEquals(320u to 240u, frame.width to frame.height)
    assertEquals(320 * 240 * 4, frame.pixels.size)
  }

  @Test
  fun aMissingCoreFails() {
    assertFailsWith<SessionException.Core> {
      GameSession.start(options(dir.resolve("missing.so").path))
    }
  }

  @Test
  fun theListenerHearsTheCoreQuit() {
    // The events test core quits on Start.
    val quit = CountDownLatch(1)
    val listener =
      object : SessionListener {
        override fun coreQuit() {
          quit.countDown()
        }

        override fun achievementEvent(event: AchievementEvent) {}
      }
    val session = start("events_libretro.so", listener)
    session.setButton(0u, Button.START, true)
    session.advance(1u)
    assertTrue(quit.await(10, TimeUnit.SECONDS))
    assertFailsWith<SessionException.Stopped> { session.advance(1u) }
  }
}
