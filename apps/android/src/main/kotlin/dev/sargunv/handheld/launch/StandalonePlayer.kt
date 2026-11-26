package dev.sargunv.handheld.launch

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.EmulatorState
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.keptSaves
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
import dev.sargunv.handheld.domain.storage.GameLayout
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

/** A note on how a play went, in plain words. A [quiet] note is a detail the Game screen omits. */
data class PlayNote(val text: String, val quiet: Boolean = false)

/**
 * A game to play, for [StandalonePlayer.play].
 *
 * @param romPath the game's file.
 * @param title the game's name, as the save conflict screen shows it.
 * @param gameId the game, as [rules] name it.
 * @param collections the collections the game is in, as [rules] name them.
 * @param romId the game's RomM id, which turns on save sync; null leaves the save alone.
 * @param saveFolders stand in for the emulator's save folders, for testing.
 * @param biosFolders take the firmware instead of the emulator's own folder, for testing.
 * @param saveServer a server to sync the save with, without a token, instead of the signed-in one.
 * @param closeEmulators whether the player turned on closing emulators: where the device can, the
 *   launcher checks whether the emulator is running, and offers to close it.
 */
data class PlayRequest(
  val romPath: String,
  val platform: PlatformSlug,
  val title: String,
  val gameId: Long,
  val romId: Long?,
  val rules: LaunchRules = LaunchRules(),
  val collections: Set<CollectionId> = emptySet(),
  val saveFolders: List<String>? = null,
  val biosFolders: List<String>? = null,
  val saveServer: String? = null,
  val closeEmulators: Boolean = false,
)

/**
 * Plays games in standalone emulators. The resolver picks the app by the user's rules, the game's
 * save syncs with RomM, the emulator's firmware goes in place, and the dispatcher starts it. When
 * the launcher is back in front, the save syncs again, which sends what the game saved. Games the
 * resolver gives the built-in session go to [BuiltInPlayer] instead.
 *
 * An emulator stays in memory after the player goes back to the library, and Android doesn't let
 * the launcher stop it, so each one it starts counts as running until the device shows otherwise
 * (see [Emulators]). While it may be, the launcher never pulls a save it may hold without asking:
 * after the game it only sends, and before the next launch it asks the player.
 */
class StandalonePlayer(
  private val activity: ComponentActivity,
  private val credentials: CredentialStore,
) : GameLauncher {
  private val dispatcher = LaunchDispatcher(activity)
  private val saves = PlaySaves(activity, credentials)
  private val builtIn = BuiltInPlayer(activity, saves)
  private val emulators = Emulators.of(activity)

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
    rom: Rom,
    rules: LaunchRules,
    collections: Set<CollectionId>,
  ): String =
    when (
      val resolution =
        resolve(LaunchSubject(rom.id, PlatformSlug(rom.platformSlug), collections), rules)
    ) {
      is LaunchResolution.Resolved -> "Opens in ${appName(resolution.target)}"
      is LaunchResolution.BuiltIn -> "Opens in the built-in player"
      is LaunchResolution.NoEmulatorInstalled -> noEmulator(resolution)
    }

  override suspend fun play(
    rom: Rom,
    path: String,
    rules: LaunchRules,
    collections: Set<CollectionId>,
    onProgress: (String) -> Unit,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String> {
    val file =
      withContext(Dispatchers.IO) { launchFile(path) }
        ?: return listOf("The game's folder is empty, so there's nothing to play.")
    val request =
      PlayRequest(
        file,
        PlatformSlug(rom.platformSlug),
        rom.title,
        rom.id,
        rom.id,
        rules,
        collections,
        closeEmulators = closeEmulators,
      )
    // A sync that fails before the game and again after it says the same thing twice.
    return play(request, onProgress, askStillRunning, chooseSave)
      .filterNot { it.quiet }
      .map { it.text }
      .distinct()
  }

  override suspend fun keptSaves(rom: Rom): List<KeptSave> =
    withContext(Dispatchers.IO) { keptSaves(saveHistory(activity), rom.id) }

  /**
   * Plays [request]'s game and returns once the launcher is back in front, or once it's clear the
   * game didn't start, with notes on what happened.
   *
   * @param onProgress hears what the launch is doing before the game starts, in a few words.
   * @param askStillRunning asks what to do when RomM has a newer save but the emulator may still
   *   hold the game.
   * @param chooseSave asks which copy of the save to keep when both changed. Null cancels the
   *   launch.
   */
  suspend fun play(
    request: PlayRequest,
    onProgress: (String) -> Unit = {},
    askStillRunning: suspend (StillRunning) -> StillRunningChoice = {
      StillRunningChoice.QuitFirst
    },
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<PlayNote> {
    plays++
    try {
      return playOnce(request, onProgress, chooseSave)
    } finally {
      plays--
    }
  }

  private suspend fun playOnce(
    request: PlayRequest,
    onProgress: (String) -> Unit,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<PlayNote> {
    val notes = mutableListOf<PlayNote>()
    fun note(text: String, quiet: Boolean = false) {
      notes += PlayNote(text, quiet)
      Log.i(TAG, text)
    }

    val resolution =
      when (
        val resolution =
          resolve(
            LaunchSubject(request.gameId, request.platform, request.collections),
            request.rules,
          )
      ) {
        is LaunchResolution.NoEmulatorInstalled -> {
          note(noEmulator(resolution))
          return notes
        }
        is LaunchResolution.BuiltIn ->
          return builtIn.play(request, resolution.core, onProgress, chooseSave).onEach {
            Log.i(TAG, it.text)
          }
        is LaunchResolution.Resolved -> resolution
      }
    val target = resolution.target
    val input =
      LaunchInput(
        request.romPath,
        request.platform,
        installedVersionName = dispatcher.versionName(target.packageName),
      )
    val command =
      when (val build = target.commandFor(input)) {
        is LaunchBuild.MissingCore -> {
          note(
            "There's no libretro core for this game's platform, so ${appName(target)} can't play it.",
          )
          return notes
        }
        is LaunchBuild.Ready -> build.command
      }
    val game =
      request.romId?.let {
        gameSaveFor(activity, target, request.platform, it, request.romPath, request.saveFolders)
      }
    if (request.romId != null && game == null) {
      note("${appName(target)}'s saves don't sync yet", quiet = true)
    }
    val packageName = target.packageName
    val canClose = request.closeEmulators && emulators.canClose
    val probe = emulators.probe(useRoot = canClose)
    if (game != null) {
      val name = appName(target)
      val synced =
        saves.beforeLaunch(
          request,
          game,
          chooseSave,
          ::note,
          emulator = emulators.state(packageName, probe),
          canClose = canClose,
          askStillRunning = { offerClose ->
            askStillRunning(StillRunning(request.title, name, offerClose))
          },
          close = { emulators.close(packageName) },
        )
      if (!synced) return notes
    }
    // A game launches even when its firmware can't be placed, since some emulators boot without it.
    placeFirmware(target, request, ::note)
    // Set from the companion's Quit, which brings the launcher to the front.
    var quit = false
    // The companion goes first, so the emulator, started second, ends up with the focus.
    PlayCompanion.show(
      activity,
      PlaySession(
        request.title,
        sync = { syncNow(request, game, target) },
        quit = if (canClose) ({ quit = true }) else null,
      ),
    )
    // Noted before the launch, so an emulator that starts counts as running even if the launcher
    // stops before it hears back. A launch that fails errs the same way.
    val before = emulators.launched(packageName, request.romId, probe)
    try {
      when (val outcome = dispatcher.launch(command)) {
        is LaunchOutcome.Started ->
          note(
            "Started ${target.packageName} (${resolution.rule}) by ${outcome.method}",
            quiet = true,
          )
        LaunchOutcome.NeedsAllFilesAccess -> {
          // Nothing started, so the emulator still holds whatever game it held.
          emulators.launchFailed(packageName, before)
          activity.startActivity(dispatcher.allFilesAccessSettings())
          note("Allow access to all files, then play again.")
          return notes
        }
        is LaunchOutcome.Failed -> {
          emulators.launchFailed(packageName, before)
          note(outcome.reason)
          return notes
        }
      }
      awaitReturn()
    } finally {
      PlayCompanion.close()
    }
    note("Back from the game", quiet = true)
    if (quit) {
      // The emulator may still be writing its save as it goes to the background.
      delay(QUIT_DELAY)
      if (!emulators.close(packageName)) {
        note("Couldn't close ${appName(target)}. Quit the game from its own menu.")
      }
    }
    when (emulators.state(packageName, probe)) {
      is EmulatorState.MayBeRunning -> note("${appName(target)} may still be running", quiet = true)
      is EmulatorState.NotRunning -> note("${appName(target)} isn't running", quiet = true)
    }
    if (game != null) saves.afterPlay(request, game, ::note)
    return notes
  }

  /**
   * Sends [game]'s save to RomM while the game runs, for the companion's Sync button, and says what
   * happened. It never pulls, since the emulator would write over a pulled save. It finishes even
   * when the companion closes, so the sync after the game sees what it sent.
   */
  private suspend fun syncNow(request: PlayRequest, game: GameSave?, target: LaunchTarget): String =
    withContext(NonCancellable) { pushNow(request, game, target) }

  private suspend fun pushNow(request: PlayRequest, game: GameSave?, target: LaunchTarget): String {
    if (game == null) {
      return "${appName(target)}'s saves don't sync yet, so there's nothing to sync."
    }
    return saves.push(request, game)
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
    return if (suggestions.isEmpty()) "No app on this device can play this game yet."
    else "No emulator for this game is installed. Install ${suggestions.joinToString(" or ")}."
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

  /** Puts [target]'s firmware for the game's platform in place, noting what happened. */
  private suspend fun placeFirmware(
    target: LaunchTarget,
    request: PlayRequest,
    note: (String, Boolean) -> Unit,
  ) {
    val result =
      try {
        installFirmwareFor(activity, credentials, target, request.platform, request.biosFolders)
      } catch (e: RommException) {
        note("Couldn't list the BIOS files on RomM: ${e.message}", false)
        return
      } catch (e: IOException) {
        note("Couldn't put the BIOS files in place: ${e.message}", false)
        return
      }
    when (result) {
      null -> Unit
      FirmwareInstall.NoneOnServer -> note("RomM has no BIOS files for ${request.platform}", true)
      is FirmwareInstall.Unreachable ->
        note(
          "Couldn't put the BIOS files where ${appName(target)} reads them: " +
            result.folders.joinToString(),
          false,
        )
      is FirmwareInstall.Done -> {
        note("Firmware in ${result.folder}", true)
        if (result.placed.isNotEmpty()) note("Placed ${result.placed.joinToString()}", true)
        if (result.kept.isNotEmpty()) note("Already there ${result.kept.joinToString()}", true)
        for ((name, reason) in result.failed) note("Couldn't put $name in place: $reason", false)
      }
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
private fun launchFile(path: String): String? {
  val folder = File(path)
  if (!folder.isDirectory) return path
  val files = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).path }.toList()
  return GameLayout.launchFile(files)?.let { File(folder, it).path }
}
