package dev.sargunv.handheld

import android.app.Activity
import androidx.compose.runtime.Composable

/**
 * The debug screen the launching intent asks for with its `debug_screen` extra, if any:
 * - `session_test`: runs a libretro core in a game session. The `core` extra names the core and the
 *   optional `content` extra the content, as paths relative to the app's `files/session-test`
 *   directory or absolute ones.
 *
 * For example:
 * ```
 * adb shell am start -n dev.sargunv.handheld/.MainActivity \
 *   --es debug_screen session_test --es core test_libretro_android.so
 * ```
 */
internal fun debugScreen(activity: Activity): (@Composable () -> Unit)? {
  val intent = activity.intent
  return when (intent.getStringExtra("debug_screen")) {
    "session_test" -> {
      val dir = activity.filesDir.resolve("session-test")
      val core = intent.getStringExtra("core")?.let { dir.resolve(it) }
      val content = intent.getStringExtra("content")?.let { dir.resolve(it) }
      ({ SessionTestScreen(dir, core, content) })
    }
    else -> null
  }
}
