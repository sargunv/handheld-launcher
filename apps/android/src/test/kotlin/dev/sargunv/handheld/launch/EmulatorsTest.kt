package dev.sargunv.handheld.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmulatorsTest {
  @Test
  fun acceptsAPackageName() {
    assertEquals("pidof xyz.aethersx2.android", isRunningCommand("xyz.aethersx2.android"))
  }

  @Test
  fun rejectsAnythingThatIsNotAPackageName() {
    for (name in listOf("", "com.armsx2; reboot", "com armsx2", "com.armsx2\n", "a".repeat(256))) {
      assertFailsWith<IllegalArgumentException>(name) { isRunningCommand(name) }
    }
  }
}
