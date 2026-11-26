package dev.sargunv.handheld.domain.emulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmulatorCommandTest {
  @Test
  fun buildsTheTwoCommandsForAPackageName() {
    assertEquals("pidof com.armsx2", EmulatorCommand.isRunning("com.armsx2").line)
    assertEquals(
      "am force-stop org.dolphinemu.dolphinemu_handheld",
      EmulatorCommand.forceStop("org.dolphinemu.dolphinemu_handheld").line,
    )
  }

  @Test
  fun rejectsAnythingThatIsNotAPackageName() {
    val bad =
      listOf(
        "",
        "com.armsx2; reboot",
        "com.armsx2 && rm -rf /",
        "com.armsx2|sh",
        "com.armsx2\nreboot",
        "com.armsx2\n",
        "com.armsx2\r",
        "com.armsx2\u0000",
        "\$(id)",
        "`id`",
        "com armsx2",
        "com.armsx2 ",
        "-f",
        "com/armsx2",
        "'com.armsx2'",
        "\"com.armsx2\"",
        "com.armsx2>out",
        "cöm.armsx2",
        "com.armsx2:service",
        "a".repeat(256),
      )
    for (name in bad) {
      assertFailsWith<IllegalArgumentException>(name) { EmulatorCommand.isRunning(name) }
      assertFailsWith<IllegalArgumentException>(name) { EmulatorCommand.forceStop(name) }
    }
  }
}
