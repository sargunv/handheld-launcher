package dev.sargunv.handheld.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileUriExposedException
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchMethod
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts games in other apps. A [LaunchCommand]'s files go out as content URIs from the app's
 * FileProvider, and the target gets read access to each file and to the folder it's in, for games
 * made of several files. A command goes by intent, or by `am start` when its recipe asks and it
 * hands over no content URIs. Some devices, such as the Thor, don't let an app run `am start`, so a
 * command it refuses goes as an intent instead.
 */
internal class LaunchDispatcher(private val context: Context) {
  private fun uriFor(path: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.launch", File(path))

  /** Whether [command] started; the log says why it didn't. */
  suspend fun launch(command: LaunchCommand): Boolean =
    (command.method == LaunchMethod.Shell && startByShell(command)) || startActivity(command)

  private fun startActivity(command: LaunchCommand): Boolean =
    try {
      for (folder in command.readFolders) {
        context.grantUriPermission(
          command.packageName,
          uriFor(folder),
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
      }
      context.startActivity(command.toIntent(::uriFor))
      true
    } catch (e: ActivityNotFoundException) {
      failed("${command.packageName} can't open it", e)
    } catch (e: SecurityException) {
      failed("${command.packageName} refused it", e)
    } catch (e: FileUriExposedException) {
      // A `file://` URI, which only `am start` can hand over.
      failed("${command.packageName} needs am start", e)
    } catch (e: IllegalArgumentException) {
      // FileProvider serves only shared storage.
      failed("Can't share the game's file with ${command.packageName}", e)
    }

  private fun failed(reason: String, cause: Exception): Boolean {
    log.w(cause) { "Launch failed: $reason" }
    return false
  }

  /** Whether `am start` started [command]. */
  private suspend fun startByShell(command: LaunchCommand): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val process = ProcessBuilder(command.amStartArgv()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // am exits with 0 when it can't start the activity, and says why on lines like
        // "Error: Activity class {...} does not exist."
        val started = process.waitFor() == 0 && output.lines().none { it.startsWith("Error") }
        if (!started) log.w { "am start refused ${command.packageName}: $output" }
        started
      } catch (e: IOException) {
        log.w(e) { "am start failed for ${command.packageName}" }
        false
      }
    }
}

private val log = Logger.withTag("LaunchDispatcher")
