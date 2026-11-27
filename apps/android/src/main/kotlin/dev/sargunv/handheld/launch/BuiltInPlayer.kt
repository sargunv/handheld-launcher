package dev.sargunv.handheld.launch

import android.app.Activity
import android.os.Build
import dev.sargunv.handheld.data.cores.InstalledCores
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.builtInGameSave
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.achievements.retroAchievementsConsole
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.saves.SaveConflict
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Plays games in the built-in session. The platform's core comes from [InstalledCores], downloaded
 * the first time and updated when a newer build is out, and the game runs in [SessionActivity],
 * with its saves in a folder of its own in [saves]. The game's save memory syncs with RomM through
 * [playSaves] before the game starts, and through its [Plays.Play] after it ends, filed under the
 * core; its save states stay on this device. [settingsFile] holds the controls and the hardcore
 * setting the game starts with.
 */
internal class BuiltInPlayer(
  private val activity: Activity,
  private val playSaves: PlaySaves,
  private val saves: BuiltInSaves,
  private val settingsFile: SettingsFile<Settings>,
) {
  private val coresDir = Path(activity.filesDir.path, "cores")

  /**
   * Plays [request]'s game in [core] as [play], and returns once the player is back from the game,
   * telling [note] what the player should know before it and returning what they should know after
   * it. [onProgress] hears how a download of the core is going, and [chooseSave] which copy of the
   * save to keep when both changed; null cancels the launch.
   */
  suspend fun play(
    play: Plays.Play,
    request: PlayRequest,
    core: LibretroCore,
    onProgress: (String) -> Unit,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
  ): List<String> {
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
        note(labelled("Core download failed", e.message))
        return emptyList()
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
      if (!playSaves.beforeLaunch(request.title, game, chooseSave, note, emulator = null))
        return emptyList()
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
    return play.builtIn(game) {
      activity.startActivity(SessionActivity.intent(activity, launch))
    }
  }
}
