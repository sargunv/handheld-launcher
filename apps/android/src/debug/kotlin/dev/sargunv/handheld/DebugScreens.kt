package dev.sargunv.handheld

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import dev.sargunv.handheld.data.cores.CoreChoices
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.domain.platform.PlatformSlug
import java.io.File
import java.io.IOException
import kotlinx.io.files.Path

/**
 * The debug screen the launching intent asks for with its `debug_screen` extra, if any:
 * - `session_test`: runs a libretro core in a game session. The `core` extra names the core and the
 *   optional `content` extra the content, as paths relative to the app's `files/session-test`
 *   directory or absolute ones. A `platform` extra, a platform slug such as `gbc`, runs that
 *   platform's core instead, installing it first through [InstalledCores] if it isn't installed or
 *   has an update. The optional `core_options` extra, a string array of `key=value`, sets core
 *   options, such as a renderer.
 * - `launch`: launches the game at the `rom` extra, an absolute path, for the platform the
 *   `platform` extra names by slug, through the launch resolver and dispatcher. The optional `app`
 *   extra names a package to launch it in, as a game's own override would. Before the launch, the
 *   emulator's firmware for the platform goes in place from the signed-in RomM server; the optional
 *   `bios_dir` extra, an absolute path, takes it instead of the emulator's own folder. With a
 *   `rom_id` extra, the game's RomM id, the game's save syncs with the server before the launch and
 *   again when the launcher is back in front. The optional `save_dir` extra, an absolute path,
 *   stands in for the emulator's save folders, and the optional `romm_url` extra names a server to
 *   sync with, without a token, instead of the signed-in one.
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
 * adb shell am start -n dev.sargunv.handheld/.MainActivity --es debug_screen launch \
 *   --es rom "'/storage/emulated/0/ROMs/ps2/Jak II.chd'" --es platform ps2 \
 *   --es app dev.sargunv.pocketgoal
 * adb shell am start -n dev.sargunv.handheld/.MainActivity --es debug_screen launch \
 *   --es rom /sdcard/ROMs/nds/game.nds --es platform nds --es rom_id 42 \
 *   --es save_dir /sdcard/Download/scratch-saves --es romm_url http://localhost:8099
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
      val pauseWhenUnfocused = settingsFile(activity).read().pauseGameWhenUnfocused
      ({ SessionTestScreen(dir, core, content, coreOptions, pauseWhenUnfocused) })
    }
    "launch" -> {
      val rom = intent.getStringExtra("rom")
      val platform = intent.getStringExtra("platform")
      val app = intent.getStringExtra("app")
      val biosDir = intent.getStringExtra("bios_dir")
      val saves =
        intent.getStringExtra("rom_id")?.toLongOrNull()?.let {
          SaveSyncOptions(it, intent.getStringExtra("save_dir"), intent.getStringExtra("romm_url"))
        }
      ({ LaunchTestScreen(activity as ComponentActivity, rom, platform, app, biosDir, saves) })
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
