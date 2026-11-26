package dev.sargunv.handheld

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import dev.sargunv.handheld.data.cores.CoreChoices
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.domain.platform.PlatformSlug
import java.io.File
import java.io.IOException
import kotlinx.io.files.Path

/**
 * The debug screen the launching intent asks for with its `debug_screen` extra, if any:
 * `session_test`, which runs a libretro core in a game session. The `core` extra names the core and
 * the optional `content` extra the content, as paths relative to the app's `files/session-test`
 * directory or absolute ones. A `platform` extra, a platform slug such as `gbc`, runs that
 * platform's core instead, installing it first through [InstalledCores] if it isn't installed or
 * has an update. The optional `core_options` extra, a string array of `key=value`, sets core
 * options, such as a renderer.
 *
 * For example:
 * ```
 * adb shell am start -n dev.sargunv.handheld/.MainActivity \
 *   --es debug_screen session_test --es core test_libretro_android.so
 * adb shell am start -n dev.sargunv.handheld/.MainActivity \
 *   --es debug_screen session_test --es platform gbc --es content ucity.gbc
 * adb shell am start -n dev.sargunv.handheld/.MainActivity \
 *   --es debug_screen session_test --es platform psp --es content game.iso \
 *   --esa core_options ppsspp_backend=vulkan
 * ```
 */
internal fun debugScreen(activity: Activity): (@Composable () -> Unit)? {
  val intent = activity.intent
  return when (intent.getStringExtra("debug_screen")) {
    "session_test" -> {
      val dir = activity.filesDir.resolve("session-test")
      val content = intent.getStringExtra("content")?.let { dir.resolve(it) }
      val platform = intent.getStringExtra("platform")
      val core: suspend () -> File? =
        if (platform != null) {
          { installedCoreFor(activity.filesDir.resolve("cores"), PlatformSlug(platform)) }
        } else {
          val core = intent.getStringExtra("core")?.let { dir.resolve(it) }
          ({ core })
        }
      val coreOptions =
        intent.getStringArrayExtra("core_options").orEmpty().associate {
          it.substringBefore('=') to it.substringAfter('=')
        }
      ({ SessionTestScreen(dir, core, content, coreOptions) })
    }
    else -> null
  }
}

/** The installed core for [platform], installed or updated first when needed, under [dir]. */
private suspend fun installedCoreFor(dir: File, platform: PlatformSlug): File {
  val root = Path(dir.path)
  val core =
    CoreChoices(Path(root, "choices.json")).coreFor(platform)
      ?: throw IOException("No libretro core runs $platform")
  // The app's native libraries are built for the device's first ABI.
  return InstalledCores(root, Build.SUPPORTED_ABIS.first()).use { cores ->
    if (cores.hasUpdate(core)) cores.install(core)
    File(cores.fileOf(core).toString())
  }
}
