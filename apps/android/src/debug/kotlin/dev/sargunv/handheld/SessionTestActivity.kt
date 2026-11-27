package dev.sargunv.handheld

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Runs a libretro core in a game session, with the extras session/README.md lists. */
class SessionTestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val dir = filesDir.resolve("session-test")
    val content = intent.getStringExtra("content")?.let { dir.resolve(it) }
    val core = intent.getStringExtra("core")?.let { dir.resolve(it) }
    val coreOptions =
      intent.getStringArrayExtra("core_options").orEmpty().associate {
        it.substringBefore('=') to it.substringAfter('=')
      }
    setContent { SessionTestScreen(dir, core, content, coreOptions) }
  }

  // A new launch closes the running session and starts its own.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    recreate()
  }
}
