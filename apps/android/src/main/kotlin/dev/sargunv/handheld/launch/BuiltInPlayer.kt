package dev.sargunv.handheld.launch

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.builtInGameSave
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.achievements.retroAchievementsConsole
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.saves.SaveConflict
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Plays games in the built-in session. The platform's core comes from [InstalledCores], downloaded
 * the first time and updated when a newer build is out, and the game runs in [SessionActivity],
 * with its saves in a folder of its own in [saves]. The game's save memory syncs with RomM through
 * [playSaves] before the game starts and after it ends, filed under the core; its save states stay
 * on this device. [settingsFile] holds the controls and the hardcore setting the game starts with.
 */
class BuiltInPlayer(
  private val activity: ComponentActivity,
  private val playSaves: PlaySaves,
  private val saves: BuiltInSaves,
  private val settingsFile: SettingsFile<Settings>,
) {
  private val coresDir = Path(activity.filesDir.path, "cores")

  /**
   * Plays [request]'s game in [core], and returns once the player is back from the game, telling
   * [note] what the player should know. [onProgress] hears how a download of the core is going, and
   * [chooseSave] which copy of the save to keep when both changed; null cancels the launch.
   */
  suspend fun play(
    request: PlayRequest,
    core: LibretroCore,
    onProgress: (String) -> Unit,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
  ) {
    val coreFile =
      try {
        // The app's native libraries are built for the device's first ABI.
        InstalledCores(coresDir, Build.SUPPORTED_ABIS.first()).use { cores ->
          cores.ensure(core) { downloaded, total ->
            val percent = total?.takeIf { it > 0 }?.let { " ${downloaded * 100 / it}%" } ?: ""
            onProgress("Downloading core$percent")
          }
        }
      } catch (e: IOException) {
        return note(listOfNotNull("Core download failed", e.message).joinToString(" · "))
      }
    val folder = saves.folderOf(request.game)
    val saveRam = saves.saveRamOf(folder, request.romPath)
    val rom = request.game as? GameKey.Romm
    val settings =
      withContext(Dispatchers.IO) {
        // Made before the sync, which pulls a save only into a folder that exists.
        File(folder).mkdirs()
        settingsFile.read()
      }
    val game = rom?.let { builtInGameSave(it.romId, core.library, File(saveRam)) }
    if (game != null) {
      if (!playSaves.beforeLaunch(request.title, game, chooseSave, note, emulator = null)) return
    }
    val launch =
      SessionLaunch(
        core = coreFile.toString(),
        content = request.romPath,
        saveDir = folder,
        saveRam = saveRam,
        states = saves.statesOf(folder, request.romPath),
        input = settings.input,
        stackedScreensOptions = core.stackedScreensOptions,
        lowerScreenWidthShare = core.lowerScreenWidthShare,
        achievementsConsole = retroAchievementsConsole(request.platform),
        hardcore = settings.achievements.hardcore,
      )
    val result = awaitResult(SessionActivity.intent(activity, launch)).data
    result?.getStringExtra(SessionActivity.EXTRA_START_ERROR)?.let {
      return note("Launch failed · $it")
    }
    val saveError = result?.getStringExtra(SessionActivity.EXTRA_SAVE_ERROR)
    if (saveError != null) note("Save write failed · $saveError")
    else log.i { "Back from the game in ${core.library}" }
    // When a write failed, the file still holds the last whole save.
    if (game != null) playSaves.afterPlay(game, note)
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

private val log = Logger.withTag("BuiltInPlayer")
