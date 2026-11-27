package dev.sargunv.handheld.launch

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.firmware.FirmwareInstaller
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StandaloneEmulator
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.keptSaves
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchResolution
import dev.sargunv.handheld.domain.launch.LaunchResolver
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.BiosKnowledge
import dev.sargunv.handheld.domain.storage.GameLayout
import dev.sargunv.handheld.handheldApp
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A game to play, for [BuiltInPlayer.play].
 *
 * @param romPath the game's file.
 * @param title the game's name, as the save conflict screen shows it.
 * @param game the game, as the built-in session files its saves. A RomM game's save syncs; a local
 *   game isn't on the server, so its save stays on the device.
 */
data class PlayRequest(
  val romPath: String,
  val platform: PlatformSlug,
  val title: String,
  val game: GameKey,
)

/**
 * Plays games on Android. The resolver picks the app by the user's rules, and games it gives the
 * built-in session go to [BuiltInPlayer]. For a standalone emulator, the game's save syncs with
 * RomM, the emulator's firmware goes in place, and the dispatcher starts it. When the launcher is
 * back in front, the save syncs again, which sends what the game saved.
 *
 * An emulator stays in memory after the player goes back to the library, and Android doesn't let
 * the launcher stop it, so each one it starts counts as running until the device shows otherwise
 * (see [Emulators]). While it may be, the launcher never pulls a save it may hold without asking:
 * after the game it only sends, and before the next launch it asks the player.
 */
class AndroidGameLauncher(private val activity: ComponentActivity) : GameLauncher {
  private val romm = activity.handheldApp.data.romm
  private val dispatcher = LaunchDispatcher(activity)
  private val saves = PlaySaves(activity.handheldApp.saveSync)
  private val builtIn = BuiltInPlayer(activity, saves)
  private val emulators = activity.handheldApp.emulators

  override val canCloseEmulators: Boolean
    get() = emulators.canClose

  private var plays = 0

  /**
   * Whether a play is under way, from the sync before the game to the one after it, in this app's
   * session or another app.
   */
  val isPlaying: Boolean
    get() = plays > 0

  override suspend fun opensIn(
    game: LibraryGame,
    rules: LaunchRules,
    collections: Set<CollectionId>,
  ): String =
    when (
      val resolution =
        resolve(LaunchSubject(game.key, PlatformSlug(game.platformSlug), collections), rules)
    ) {
      is LaunchResolution.Resolved -> "Opens in ${appName(resolution.target)}"
      is LaunchResolution.BuiltIn -> "Opens in Built-in"
      is LaunchResolution.NoEmulatorInstalled -> noEmulator(resolution)
    }

  override suspend fun keptSaves(game: LibraryGame): List<KeptSave> {
    // Save sync keeps copies only of the saves it syncs, in the signed-in account's history.
    val history = romm.account?.saveHistory ?: return emptyList()
    if (game !is Rom) return emptyList()
    return withContext(Dispatchers.IO) { keptSaves(File(history.toString()), game.id) }
  }

  override suspend fun play(
    game: LibraryGame,
    path: String,
    rules: LaunchRules,
    collections: Set<CollectionId>,
    onProgress: (String) -> Unit,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String> {
    val file = withContext(Dispatchers.IO) { launchFile(path) } ?: return listOf("Folder empty")
    val request = PlayRequest(file, PlatformSlug(game.platformSlug), game.title, game.key)
    // A set, since a sync that fails before the game and again after it says the same thing twice.
    val notes = linkedSetOf<String>()
    fun note(text: String) {
      notes += text
      Log.i(TAG, text)
    }

    plays++
    try {
      when (
        val resolution = resolve(LaunchSubject(request.game, request.platform, collections), rules)
      ) {
        is LaunchResolution.NoEmulatorInstalled -> note(noEmulator(resolution))
        is LaunchResolution.BuiltIn ->
          builtIn.play(request, resolution.core, onProgress, chooseSave, ::note)
        is LaunchResolution.Resolved ->
          playStandalone(request, resolution, closeEmulators, askStillRunning, chooseSave, ::note)
      }
    } finally {
      plays--
    }
    return notes.toList()
  }

  /** Plays [request]'s game in the standalone emulator [resolution] picked. */
  private suspend fun playStandalone(
    request: PlayRequest,
    resolution: LaunchResolution.Resolved,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
  ) {
    val target = resolution.target
    val packageName = target.packageName
    val input =
      LaunchInput(
        request.romPath,
        request.platform,
        installedVersionName = dispatcher.versionName(packageName),
      )
    val command =
      when (val build = target.commandFor(input)) {
        is LaunchBuild.MissingCore -> return note("No core for this platform · ${appName(target)}")
        is LaunchBuild.Ready -> build.command
      }
    val save = lookUpSave(request, target, note)
    val probe = emulators.probe(useRoot = closeEmulators)
    if (save != null) {
      val name = appName(target)
      val emulator =
        StandaloneEmulator(
          emulators.state(packageName, probe),
          askStillRunning = { offerClose ->
            askStillRunning(StillRunning(request.title, name, offerClose))
          },
          close = if (closeEmulators) ({ emulators.close(packageName) }) else null,
        )
      val synced = saves.beforeLaunch(request.title, save, chooseSave, note, emulator)
      if (!synced) return
    }
    // A game launches even when its firmware can't be placed: some emulators boot without it.
    placeFirmware(target, request.platform, note)
    // Set from the companion's Quit, which brings the launcher to the front.
    var quit = false
    // The companion goes first, so the emulator, started second, ends up with the focus.
    PlayCompanion.show(
      activity,
      PlaySession(
        request.title,
        sync = { syncNow(save, target) },
        quit = if (closeEmulators) ({ quit = true }) else null,
      ),
    )
    // Noted before the launch, so an emulator that starts counts as running even if the launcher
    // stops before it hears back. A launch that fails errs the same way.
    val before = emulators.launched(packageName, (request.game as? GameKey.Romm)?.romId, probe)
    try {
      when (val outcome = dispatcher.launch(command)) {
        is LaunchOutcome.Started ->
          Log.i(TAG, "Started $packageName (${resolution.rule}) by ${outcome.method}")
        LaunchOutcome.NeedsAllFilesAccess -> {
          // Nothing started, so the emulator still holds whatever game it held.
          emulators.launchFailed(packageName, before)
          activity.startActivity(activity.handheldApp.allFilesAccessSettings())
          return note("Needs all-files access")
        }
        is LaunchOutcome.Failed -> {
          emulators.launchFailed(packageName, before)
          return note(outcome.reason)
        }
      }
      awaitReturn()
    } finally {
      PlayCompanion.close()
    }
    if (quit) {
      // The emulator may still be writing its save as it goes to the background.
      delay(QUIT_DELAY)
      if (!emulators.close(packageName)) {
        note("Close failed · ${appName(target)}")
      }
    }
    if (save != null) saves.afterPlay(save, note)
  }

  /** [request]'s save as [target] keeps it, or null when it doesn't sync. */
  private suspend fun lookUpSave(
    request: PlayRequest,
    target: LaunchTarget,
    note: (String) -> Unit,
  ): GameSave? {
    val romId = (request.game as? GameKey.Romm)?.romId ?: return null
    val lookup =
      withContext(Dispatchers.IO) {
        gameSaveFor(activity, target, request.platform, romId, request.romPath)
      }
    when (lookup) {
      is SaveLookup.Found -> Log.i(TAG, "The save syncs as ${lookup.save}")
      SaveLookup.NotSupported -> Log.i(TAG, "${target.packageName}'s saves don't sync yet")
      SaveLookup.SharedCards ->
        Log.i(TAG, "${target.packageName} keeps its saves on a shared memory card")
      SaveLookup.NoGameId -> note("Save won't sync · No game id in the file")
    }
    return (lookup as? SaveLookup.Found)?.save
  }

  /**
   * Sends [save] to RomM while the game runs, for the companion's Sync button, and says what
   * happened. It never pulls, since the emulator would write over a pulled save. It finishes even
   * when the companion closes, so the sync after the game sees what it sent.
   */
  private suspend fun syncNow(save: GameSave?, target: LaunchTarget): String =
    withContext(NonCancellable) {
      if (save == null) "Save sync unsupported · ${appName(target)}" else saves.push(save)
    }

  private suspend fun resolve(subject: LaunchSubject, rules: LaunchRules) =
    withContext(Dispatchers.IO) {
      LaunchResolver.resolve(subject, rules, dispatcher.installedPackages())
    }

  private fun appName(target: LaunchTarget): String =
    target.emulator?.displayName
      ?: runCatching {
          val info = activity.packageManager.getApplicationInfo(target.packageName, 0)
          activity.packageManager.getApplicationLabel(info).toString()
        }
        .getOrDefault(target.packageName)

  private fun noEmulator(resolution: LaunchResolution.NoEmulatorInstalled): String {
    val suggestions = resolution.suggestions.take(2).map { it.displayName }
    return if (suggestions.isEmpty()) "No emulator"
    else "No emulator · Install ${suggestions.joinToString(" or ")}"
  }

  /**
   * The emulator can't be watched for quitting, so the game counts as over when the launcher is
   * back in front after leaving it.
   */
  private suspend fun awaitReturn() {
    val states = activity.lifecycle.currentStateFlow
    withTimeoutOrNull(10.seconds) { states.first { !it.isAtLeast(Lifecycle.State.RESUMED) } }
    states.first { it.isAtLeast(Lifecycle.State.RESUMED) }
  }

  /**
   * Puts the firmware [target]'s emulator needs for [platform] where it reads it, from the
   * signed-in RomM server, noting what went wrong.
   */
  private suspend fun placeFirmware(
    target: LaunchTarget,
    platform: PlatformSlug,
    note: (String) -> Unit,
  ) {
    val emulator = target.emulator ?: return
    val location = BiosKnowledge.locationFor(emulator.id, platform) ?: return
    val client = romm.account?.client ?: return
    val storage = activity.handheldApp.storageTargets().firstOrNull()?.root ?: return
    val result =
      try {
        FirmwareInstaller(client, File(activity.cacheDir, "firmware"))
          .install(platform, location, location.folders(storage, target.packageName))
      } catch (e: RommException) {
        note("BIOS list failed · ${e.message}")
        return
      } catch (e: IOException) {
        note("BIOS not placed · ${e.message}")
        return
      }
    Log.i(TAG, "Firmware for $platform: $result")
    when (result) {
      is FirmwareInstall.Unreachable ->
        note("BIOS not placed · Can't write ${result.folders.joinToString()}")
      is FirmwareInstall.Done ->
        for ((name, reason) in result.failed) note("BIOS not placed · $name: $reason")
      FirmwareInstall.NoneOnServer -> Unit
    }
  }

  private companion object {
    const val TAG = "Play"
    val QUIT_DELAY = 1.seconds
  }
}

/**
 * The file to launch for a game at [path]: the path itself for a file, or for a folder, the file an
 * emulator opens among those in it and its subfolders. Null for an empty folder.
 */
internal fun launchFile(path: String): String? {
  val folder = File(path)
  if (!folder.isDirectory) return path
  val files = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).path }.toList()
  return GameLayout.launchFile(files)?.let { File(folder, it).path }
}
