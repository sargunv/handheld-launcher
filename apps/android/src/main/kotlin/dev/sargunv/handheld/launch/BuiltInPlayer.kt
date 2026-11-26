package dev.sargunv.handheld.launch

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.sargunv.handheld.data.cores.CoreChoices
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.builtInGameSave
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.settingsFile
import dev.sargunv.handheld.ui.saves.SaveConflict
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Plays games in the built-in session. The platform's core comes from the core manager, downloaded
 * the first time and updated when a newer build is out, and the game runs in [SessionActivity],
 * with its saves in a folder of its own in the app's files. The game's save memory syncs with RomM
 * through [playSaves] before the game starts and after it ends, filed under the core; its save
 * states stay on this device.
 */
class BuiltInPlayer(private val activity: ComponentActivity, private val playSaves: PlaySaves) {
  private val coresDir = Path(activity.filesDir.path, "cores")
  private val saves = BuiltInSaves(File(activity.filesDir, "saves").path)

  /**
   * Plays [request]'s game in [core], a libretro core's library name, or the platform's chosen core
   * when it's null, and returns once the player is back from the game, with notes on what happened.
   * [onProgress] hears how a download of the core is going, and [chooseSave] which copy of the save
   * to keep when both changed; null cancels the launch.
   */
  suspend fun play(
    request: PlayRequest,
    core: String?,
    onProgress: (String) -> Unit,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
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
    val notes = mutableListOf<PlayNote>()
    fun note(text: String, quiet: Boolean = false) {
      notes += PlayNote(text, quiet)
    }

    val folder = saves.folderOf(request.gameId)
    val saveRam = saves.saveRamOf(request.gameId, request.romPath)
    val settings =
      withContext(Dispatchers.IO) {
        // Made before the sync, which pulls a save only into a folder that exists.
        File(folder).mkdirs()
        settingsFile(activity).read()
      }
    val game = request.romId?.let { builtInGameSave(it, chosen.library, File(saveRam)) }
    if (game != null && !playSaves.beforeLaunch(request, game, chooseSave, ::note)) return notes
    val intent =
      SessionActivity.intent(
        activity,
        core = coreFile.toString(),
        content = request.romPath,
        systemDir = File(activity.filesDir, "system").path,
        saveDir = folder,
        saveRam = saveRam,
        states = saves.statesOf(request.gameId, request.romPath),
        pauseWhenUnfocused = settings.pauseGameWhenUnfocused,
        aButtonPosition = settings.input.aButtonPosition,
      )
    val result = awaitResult(intent)
    val error = result.data?.getStringExtra(SessionActivity.EXTRA_ERROR)
    val saveError = result.data?.getStringExtra(SessionActivity.EXTRA_SAVE_ERROR)
    if (error != null) {
      note("Couldn't start the game: $error")
      return notes
    }
    if (saveError != null) note("The game's save couldn't be written: $saveError")
    else note("Back from the game in ${chosen.library}", quiet = true)
    // The session writes its save memory as it stops, but one that ends without stopping, such as
    // when the launcher comes to the front over it, writes as it's destroyed, which can come after
    // this. So this only sends: a newer save on RomM waits for the next launch, where a change on
    // both sides is a conflict. When a write failed, the file still holds the last whole save.
    if (game != null) playSaves.afterPlay(request, game, ::note, pull = false)
    return notes
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
