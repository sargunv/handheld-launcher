package dev.sargunv.handheld.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.FileUriExposedException
import androidx.core.content.FileProvider
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.launch.android.ContentUris
import dev.sargunv.handheld.domain.launch.android.LaunchIntents
import dev.sargunv.handheld.domain.launch.android.ShellLaunch
import dev.sargunv.handheld.handheldApp
import dev.sargunv.handheld.launcherApps
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of [LaunchDispatcher.launch]. */
sealed interface LaunchOutcome {
  data class Started(val method: LaunchMethod) : LaunchOutcome

  /**
   * The command hands over a file the launcher can't read without all-files access, which it
   * doesn't have.
   */
  data object NeedsAllFilesAccess : LaunchOutcome

  data class Failed(val reason: String) : LaunchOutcome
}

/**
 * Starts games in other apps. A [LaunchCommand]'s files go out as content URIs from the app's
 * FileProvider, and the target gets read access to each file and to the folder it's in, for games
 * made of several files. A command goes by intent, or by `am start` when its recipe asks and it
 * hands over no content URIs. Some devices, such as the Thor, don't let an app run `am start`, so a
 * command it refuses goes as an intent instead.
 */
class LaunchDispatcher(private val context: Context) {
  private val contentUris = ContentUris { path ->
    FileProvider.getUriForFile(context, "${context.packageName}.launch", File(path))
  }

  /** The installed apps a game can launch in: every app with a launcher icon. */
  fun installedPackages(): Set<String> =
    launcherApps(context).mapTo(mutableSetOf()) { it.packageName }

  /** The version name of [packageName], for recipes that depend on the installed build. */
  fun versionName(packageName: String): String? = runCatching {
    context.packageManager.getPackageInfo(packageName, 0).versionName
  }
    .getOrNull()

  suspend fun launch(command: LaunchCommand): LaunchOutcome {
    if (command.readGrants.isNotEmpty() && !context.handheldApp.allFilesAccess.value) {
      return LaunchOutcome.NeedsAllFilesAccess
    }
    if (command.dispatchMethod == LaunchMethod.Shell && startByShell(command)) {
      return LaunchOutcome.Started(LaunchMethod.Shell)
    }
    return startActivity(command)
  }

  private fun startActivity(command: LaunchCommand): LaunchOutcome =
    try {
      for (folder in command.readFolders) {
        context.grantUriPermission(
          command.packageName,
          contentUris.forFile(folder),
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
      }
      context.startActivity(LaunchIntents.toIntent(command, contentUris))
      LaunchOutcome.Started(LaunchMethod.Intent)
    } catch (e: ActivityNotFoundException) {
      LaunchOutcome.Failed("${command.packageName} can't open this: ${e.message}")
    } catch (e: SecurityException) {
      LaunchOutcome.Failed("${command.packageName} refused the launch: ${e.message}")
    } catch (e: FileUriExposedException) {
      // A `file://` URI, which only `am start` can hand over.
      LaunchOutcome.Failed("${command.packageName} needs am start, which this device refused")
    } catch (e: IllegalArgumentException) {
      // FileProvider serves only shared storage.
      LaunchOutcome.Failed("Can't hand over the game's file: ${e.message}")
    }

  /** Whether `am start` started [command]. */
  private suspend fun startByShell(command: LaunchCommand): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val process = ProcessBuilder(ShellLaunch.argv(command)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // am exits with 0 when it can't start the activity, and says why on lines like
        // "Error: Activity class {...} does not exist."
        process.waitFor() == 0 && output.lines().none { it.startsWith("Error") }
      } catch (e: IOException) {
        false
      }
    }
}
