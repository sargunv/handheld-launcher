package dev.sargunv.handheld.data.saves

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LaunchedEmulatorsTest {
  private val root = Files.createTempDirectory("launched-emulators-test").toFile()
  private val path = File(root, "launched.json")

  @AfterTest fun deleteRoot() = check(root.deleteRecursively())

  private class FakeProbe : EmulatorProbe {
    var boot = Boot(count = 3, bootedAt = 1_000_000, sinceBoot = 60_000)
    val stopped = mutableSetOf<String>()
    var running: Boolean? = null
    val asked = mutableListOf<String>()

    override fun boot() = boot

    override fun isStopped(packageName: String) = packageName in stopped

    override suspend fun isRunning(packageName: String): Boolean? {
      asked += packageName
      return running
    }
  }

  private val armsx2 = "com.armsx2"

  @Test
  fun anEmulatorTheLauncherStartedMayBeRunningUntilSomethingShowsItStopped() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe), "Not launched yet")

    emulators.launched(armsx2, romId = 7, probe)
    probe.boot = probe.boot.copy(sinceBoot = 600_000)
    val state = assertIs<EmulatorState.MayBeRunning>(emulators.state(armsx2, probe))
    assertEquals(7, state.romId)
    assertTrue(state.holds(7))
    assertFalse(state.holds(8), "It holds only the game it was started with")
    assertIs<EmulatorState.MayBeRunning>(
      LaunchedEmulators(path).state(armsx2, probe),
      "The record outlives the launcher",
    )
    assertIs<EmulatorState.NotRunning>(emulators.state("org.ppsspp.ppsspp", probe))
  }

  @Test
  fun aStoppedAppIsntRunning() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launched(armsx2, romId = 7, probe)

    probe.stopped += armsx2
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe))
    probe.stopped -= armsx2
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe), "The record is gone")
  }

  @Test
  fun aRestartStopsEveryApp() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launched(armsx2, romId = 7, probe)

    probe.boot = Boot(count = 4, bootedAt = 9_000_000, sinceBoot = 900_000)
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe))
  }

  @Test
  fun withoutABootCountTheTimeSinceBootTellsARestart() {
    val launched = Boot(count = null, bootedAt = 1_000_000, sinceBoot = 600_000)
    // The same boot, with the clock corrected by a few seconds.
    assertFalse(Boot(null, 1_004_000, 900_000).isAfter(launched))
    // The time since boot went back.
    assertTrue(Boot(null, 1_000_000, 30_000).isAfter(launched))
    // A later boot that has run longer than the earlier one had.
    assertTrue(Boot(null, 5_000_000, 700_000).isAfter(launched))
  }

  @Test
  fun theDeviceSayingItIsntRunningStopsIt() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launched(armsx2, romId = 7, probe)

    probe.running = true
    assertIs<EmulatorState.MayBeRunning>(emulators.state(armsx2, probe))
    probe.running = false
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe))
    probe.running = true
    assertIs<EmulatorState.NotRunning>(
      emulators.state(armsx2, probe),
      "It only counts once the launcher starts it again",
    )
  }

  @Test
  fun closingItForgetsIt() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launched(armsx2, romId = 7, probe)
    emulators.stopped(armsx2)
    assertIs<EmulatorState.NotRunning>(emulators.state(armsx2, probe))
  }

  @Test
  fun aLaunchThatDidntStartLeavesTheGameTheEmulatorHolds() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launched(armsx2, romId = 7, probe)

    val before = emulators.launched(armsx2, romId = 8, probe)
    emulators.launchFailed(armsx2, before)
    val state = assertIs<EmulatorState.MayBeRunning>(emulators.state(armsx2, probe))
    assertEquals(7, state.romId)
  }

  @Test
  fun aFirstLaunchThatFailedStillCountsAsRunning() = runTest {
    val probe = FakeProbe()
    val emulators = LaunchedEmulators(path)
    emulators.launchFailed(armsx2, emulators.launched(armsx2, romId = 8, probe))
    assertIs<EmulatorState.MayBeRunning>(emulators.state(armsx2, probe))
  }
}
