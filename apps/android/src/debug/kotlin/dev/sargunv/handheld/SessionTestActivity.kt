package dev.sargunv.handheld

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformSlug
import java.io.File
import java.io.IOException
import kotlinx.io.files.Path

/** Runs a libretro core in a game session, with the extras session/README.md lists. */
class SessionTestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val dir = filesDir.resolve("session-test")
    val content = intent.getStringExtra("content")?.let { dir.resolve(it) }
    val platform = intent.getStringExtra("platform")
    val core: suspend () -> File? =
      if (platform != null) {
        { installedCoreFor(filesDir.resolve("cores"), PlatformSlug(platform)) }
      } else {
        val core = intent.getStringExtra("core")?.let { dir.resolve(it) }
        ({ core })
      }
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

/** The installed core for [platform], installed or updated first when needed, under [dir]. */
private suspend fun installedCoreFor(dir: File, platform: PlatformSlug): File {
  val core =
    LibretroCores.recommendedFor(platform) ?: throw IOException("No libretro core runs $platform")
  // The app's native libraries are built for the device's first ABI.
  return InstalledCores(Path(dir.path), Build.SUPPORTED_ABIS.first()).use { cores ->
    File(cores.ensure(core).toString())
  }
}
