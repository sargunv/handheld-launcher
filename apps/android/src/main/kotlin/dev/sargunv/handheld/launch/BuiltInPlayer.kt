package dev.sargunv.handheld.launch

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.sargunv.handheld.data.cores.CoreChoices
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.settingsFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Plays games in the built-in session. The platform's core comes from the core manager, downloaded
 * the first time and updated when a newer build is out, and the game runs in [SessionActivity],
 * with its saves in a folder of its own in the app's files.
 */
class BuiltInPlayer(private val activity: ComponentActivity) {
  private val coresDir = Path(activity.filesDir.path, "cores")
  private val saves = BuiltInSaves(File(activity.filesDir, "saves").path)

  /**
   * Plays [request]'s game in [core], a libretro core's library name, or the platform's chosen core
   * when it's null, and returns once the player is back from the game, with notes on what happened.
   * [onProgress] hears how a download of the core is going.
   */
  suspend fun play(
    request: PlayRequest,
    core: String?,
    onProgress: (String) -> Unit,
  ): List<PlayNote> {
    val chosen =
      core?.let(LibretroCores::byLibrary)
        ?: CoreChoices(Path(coresDir, "choices.json")).coreFor(request.platform)
        ?: return listOf(PlayNote("No libretro core runs this game's platform."))
    val coreFile =
      try {
        // The app's native libraries are built for the device's first ABI.
        InstalledCores(coresDir, Build.SUPPORTED_ABIS.first()).use { cores ->
          cores.ensure(chosen) { downloaded, total ->
            val percent = total?.takeIf { it > 0 }?.let { " ${downloaded * 100 / it}%" } ?: ""
            onProgress("Getting the core$percent")
          }
        }
      } catch (e: IOException) {
        return listOf(PlayNote("Couldn't download the game's core: ${e.message}"))
      }
    val folder = saves.folderOf(request.gameId)
    val pauseWhenUnfocused =
      withContext(Dispatchers.IO) {
        File(folder).mkdirs()
        settingsFile(activity).read().pauseGameWhenUnfocused
      }
    val intent =
      SessionActivity.intent(
        activity,
        core = coreFile.toString(),
        content = request.romPath,
        systemDir = File(activity.filesDir, "system").path,
        saveDir = folder,
        saveRam = saves.saveRamOf(request.gameId, request.romPath),
        pauseWhenUnfocused = pauseWhenUnfocused,
      )
    val result = awaitResult(intent)
    val error = result.data?.getStringExtra(SessionActivity.EXTRA_ERROR)
    return if (error != null) listOf(PlayNote("Couldn't start the game: $error"))
    else listOf(PlayNote("Back from the game in ${chosen.library}", quiet = true))
  }

  /** Starts [intent] and waits for the activity it starts to finish. */
  private suspend fun awaitResult(intent: Intent): ActivityResult {
    val result = CompletableDeferred<ActivityResult>()
    val launcher =
      activity.activityResultRegistry.register(
        "builtin-session",
        ActivityResultContracts.StartActivityForResult(),
      ) {
        result.complete(it)
      }
    try {
      launcher.launch(intent)
      return result.await()
    } finally {
      launcher.unregister()
    }
  }
}
