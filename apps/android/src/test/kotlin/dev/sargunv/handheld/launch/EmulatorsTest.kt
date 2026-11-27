package dev.sargunv.handheld.launch

import kotlin.test.Test
import kotlin.test.assertFailsWith

class EmulatorsTest {
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
      assertFailsWith<IllegalArgumentException>(name) { isRunningCommand(name) }
    }
  }
}
