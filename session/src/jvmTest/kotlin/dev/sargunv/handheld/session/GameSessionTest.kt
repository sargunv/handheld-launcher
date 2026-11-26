package dev.sargunv.handheld.session

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The session through its Kotlin bindings, against the cores that `mise run session:fixtures`
 * builds. libretro-samples' test core draws a red and green checkerboard that the D-pad scrolls a
 * pixel a frame.
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

  private fun start(
    core: String,
    content: String? = null,
    listener: SessionListener? = null,
  ): GameSession =
    GameSession.start(
        SessionOptions(
          corePath = fixture(core).path,
          contentPath = content?.let { fixture(it).path },
          systemDir = dir.resolve("system").path,
          saveDir = dir.resolve("saves").path,
          saveRamPath = dir.resolve("saves/game.srm").path,
          coreOptions = emptyMap(),
          video = VideoMode.OFFSCREEN,
        ),
        listener,
      )
      .also { sessions += it }

  private fun GameSession.frame(): CapturedFrame = assertNotNull(captureFrame())

  /** The RGBA bytes of the pixel at ([x], [y]). */
  private fun CapturedFrame.pixel(x: Int, y: Int): List<Byte> {
    val i = (y * width.toInt() + x) * 4
    return pixels.copyOfRange(i, i + 4).toList()
  }

  @Test
  fun runsTheTestCore() {
    val session = start("test_libretro.so")
    val info = session.info()
    assertEquals(320u, info.baseWidth)
    assertEquals(null, session.captureFrame())

    session.advance(1u)
    val frame = session.frame()
    assertEquals(320u to 240u, frame.width to frame.height)
    assertEquals(320 * 240 * 4, frame.pixels.size)
    assertEquals(listOf<Byte>(0, -1, 0, -1), frame.pixel(0, 0))
    assertEquals(listOf<Byte>(-1, 0, 0, -1), frame.pixel(16, 0))
  }

  @Test
  fun inputMovesThePicture() {
    val session = start("test_libretro.so")
    session.advance(1u)
    val before = session.frame()
    session.setButton(0u, Button.RIGHT, true)
    session.advance(3u)
    session.setButton(0u, Button.RIGHT, false)
    val after = session.frame()
    // The core scrolls once for each of the two ports it reads.
    assertNotEquals(before.pixel(20, 0), after.pixel(20, 0))
    assertEquals(before.pixel(14, 0), after.pixel(20, 0))
  }

  @Test
  fun statesRoundTrip() {
    val session = start("test_libretro.so")
    session.setButton(0u, Button.DOWN, true)
    session.advance(5u)
    val state = dir.resolve("states/slot1.state").path
    session.saveState(state)
    session.advance(1u)
    val saved = session.frame()

    session.advance(10u)
    assertFalse(saved.pixels.contentEquals(session.frame().pixels))
    session.loadState(state)
    session.advance(1u)
    assertContentEquals(saved.pixels, session.frame().pixels)
  }

  @Test
  fun saveMemoryLoadsAtStartAndIsWrittenWhenItChanges() {
    val save = dir.resolve("saves/game.srm")
    val state = dir.resolve("blank.state").path
    // A state of the game with blank save memory, whose battery-backed RAM a state includes.
    start("gambatte_libretro.so", "ucity.gbc").use { session ->
      session.saveState(state)
      session.stop()
    }
    assertFalse(save.exists(), "unchanged save memory isn't written")

    val pattern = ByteArray(128 * 1024) { (it * 7 % 251).toByte() }
    save.parentFile.mkdirs()
    save.writeBytes(pattern)
    start("gambatte_libretro.so", "ucity.gbc").use { session ->
      session.pause()
      assertContentEquals(pattern, save.readBytes())
      session.loadState(state)
      session.pause()
    }
    val written = save.readBytes()
    assertEquals(pattern.size, written.size)
    assertFalse(pattern.contentEquals(written))
  }

  @Test
  fun anUnreadableSaveFileFailsTheStart() {
    // A directory where the save file goes can't be read as one.
    val save = dir.resolve("saves/game.srm")
    save.mkdirs()
    assertFailsWith<SessionException.ReadSaveRam> { start("gambatte_libretro.so", "ucity.gbc") }
    assertTrue(save.isDirectory)
    // The failed start released the session.
    start("test_libretro.so")
  }

  @Test
  fun theListenerHearsTheCoreChangeModeAndQuit() {
    // The events test core switches its video mode on Select and quits on Start.
    val heard = LinkedBlockingQueue<Any>()
    val listener =
      object : SessionListener {
        override fun infoChanged(info: SessionInfo) {
          heard.put(info)
        }

        override fun coreQuit() {
          heard.put("quit")
        }

        override fun achievementEvent(event: AchievementEvent) {}
      }
    val session = start("events_libretro.so", listener = listener)
    session.setButton(0u, Button.SELECT, true)
    session.advance(1u)
    val info = assertIs<SessionInfo>(heard.poll(10, TimeUnit.SECONDS))
    assertEquals(384u to 216u, info.baseWidth to info.baseHeight)
    assertEquals(info, session.info())

    session.setButton(0u, Button.START, true)
    session.advance(1u)
    assertEquals("quit", heard.poll(10, TimeUnit.SECONDS))
    assertFailsWith<SessionException.Stopped> { session.advance(1u) }
  }

  @Test
  fun stoppedSessionsRefuseWork() {
    val session = start("test_libretro.so")
    session.stop()
    session.stop()
    assertFailsWith<SessionException.Stopped> { session.advance(1u) }
    assertFailsWith<SessionException.Stopped> { session.saveState(dir.resolve("s").path) }
  }

  @Test
  fun oneSessionAtATime() {
    start("test_libretro.so")
    assertFailsWith<SessionException.AlreadyActive> { start("test_libretro.so") }
  }

  @Test
  fun aMissingCoreFails() {
    assertFailsWith<SessionException.Core> {
      GameSession.start(
        SessionOptions(
          corePath = dir.resolve("missing.so").path,
          contentPath = null,
          systemDir = dir.path,
          saveDir = dir.path,
          saveRamPath = null,
          coreOptions = emptyMap(),
          video = VideoMode.OFFSCREEN,
        ),
      )
    }
  }

  @Test
  fun windowsNeedAWindowSystem() {
    val session = start("test_libretro.so")
    assertFailsWith<SessionException.UnsupportedWindow> {
      session.attachWindow(WindowHandle.Android(0u), 1u, 1u)
    }
  }
}
