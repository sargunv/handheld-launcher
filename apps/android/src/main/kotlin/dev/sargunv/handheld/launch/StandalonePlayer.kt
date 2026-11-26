package dev.sargunv.handheld.launch

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.data.saves.keptSaves
import dev.sargunv.handheld.domain.Rom
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
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * @param romId the game's RomM id, which turns on save sync; null leaves the save alone.
 * @param saveFolders stand in for the emulator's save folders, for testing.
 * @param biosFolders take the firmware instead of the emulator's own folder, for testing.
 * @param saveServer a server to sync the save with, without a token, instead of the signed-in one.
 */
data class PlayRequest(
  val romPath: String,
  val platform: PlatformSlug,
  val title: String,
  val gameId: Long,
  val romId: Long?,
  val rules: LaunchRules = LaunchRules(),
  val saveFolders: List<String>? = null,
  val biosFolders: List<String>? = null,
  val saveServer: String? = null,
)

/**
 * Plays games in standalone emulators. The resolver picks the app by the user's rules, the game's
 * save syncs with RomM, the emulator's firmware goes in place, and the dispatcher starts it. When
 * the launcher is back in front, the save syncs again, which sends what the game saved.
 *
 * The user can't set rules yet, so every game opens in the best installed emulator for its
 * platform.
 */
class StandalonePlayer(
  private val activity: ComponentActivity,
  private val credentials: CredentialStore,
) : GameLauncher {
  private val dispatcher = LaunchDispatcher(activity)

  // The companion's sync and the one after the game share the save's records, so one waits for
  // the other.
  private val syncLock = Mutex()

  override suspend fun opensIn(rom: Rom): String =
    when (val resolution = resolve(rom.id, PlatformSlug(rom.platformSlug), LaunchRules())) {
      is LaunchResolution.Resolved -> "Opens in ${appName(resolution.target)}"
      is LaunchResolution.NoEmulatorInstalled -> noEmulator(resolution)
    }

  override suspend fun play(
    rom: Rom,
    path: String,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String> {
    val file =
      withContext(Dispatchers.IO) { launchFile(path) }
        ?: return listOf("The game's folder is empty, so there's nothing to play.")
    val request = PlayRequest(file, PlatformSlug(rom.platformSlug), rom.title, rom.id, rom.id)
    return play(request, chooseSave).filterNot { it.quiet }.map { it.text }
  }

  override suspend fun keptSaves(rom: Rom): List<KeptSave> =
    withContext(Dispatchers.IO) { keptSaves(saveHistory(activity), rom.id) }

  /**
   * Plays [request]'s game and returns once the launcher is back in front, or once it's clear the
   * game didn't start, with notes on what happened.
   *
   * @param chooseSave asks which copy of the save to keep when both changed. Null cancels the
   *   launch.
   */
  suspend fun play(
    request: PlayRequest,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<PlayNote> {
    val notes = mutableListOf<PlayNote>()
    fun note(text: String, quiet: Boolean = false) {
      notes += PlayNote(text, quiet)
      Log.i(TAG, text)
    }

    val resolution =
      when (val resolution = resolve(request.gameId, request.platform, request.rules)) {
        is LaunchResolution.NoEmulatorInstalled -> {
          note(noEmulator(resolution))
          return notes
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
    if (game != null && !syncBeforeLaunch(request, game, chooseSave, ::note)) return notes
    // A game launches even when its firmware can't be placed, since some emulators boot without it.
    placeFirmware(target, request, ::note)
    // The companion goes first, so the emulator, started second, ends up with the focus.
    PlayCompanion.show(activity, PlaySession(request.title) { syncNow(request, game, target) })
    try {
      when (val outcome = dispatcher.launch(command)) {
        is LaunchOutcome.Started ->
          note(
            "Started ${target.packageName} (${resolution.rule}) by ${outcome.method}",
            quiet = true,
          )
        LaunchOutcome.NeedsAllFilesAccess -> {
          activity.startActivity(dispatcher.allFilesAccessSettings())
          note("Allow access to all files, then play again.")
          return notes
        }
        is LaunchOutcome.Failed -> {
          note(outcome.reason)
          return notes
        }
      }
      awaitReturn()
    } finally {
      PlayCompanion.close()
    }
    note("Back from the game", quiet = true)
    if (game != null) syncAfterReturn(request, game, ::note)
    return notes
  }

  /**
   * Sends [game]'s save to RomM while the game runs, for the companion's Sync button, and says what
   * happened. It never pulls, since the emulator would write over a pulled save.
   */
  private suspend fun syncNow(request: PlayRequest, game: GameSave?, target: LaunchTarget): String {
    if (game == null) {
      return "${appName(target)}'s saves don't sync yet, so there's nothing to sync."
    }
    val (client, account) = saveClient(request) ?: return "Not signed in, so the save can't sync."
    return client.use {
      try {
        syncLock.withLock {
          when (saveSync(activity, it, account).push(game)) {
            SaveSyncResult.NoSave -> "There's no save yet, so there's nothing to sync."
            is SaveSyncResult.Pushed -> "Sent the save to RomM."
            is SaveSyncResult.ServerNewer ->
              "RomM has a newer save, which comes to this device when you're back in the library."
            is SaveSyncResult.Conflict ->
              "The save changed on RomM during the game too, so you'll choose which to keep the " +
                "next time you play."
            is SaveSyncResult.UpToDate -> "RomM already has this save."
            // A push never writes to the device or settles a conflict.
            is SaveSyncResult.Pulled,
            is SaveSyncResult.NoFolder,
            is SaveSyncResult.Resolved -> "The save synced."
          }
        }
      } catch (e: RommException) {
        syncFailed(e)
      } catch (e: IOException) {
        syncFailed(e)
      }
    }
  }

  private suspend fun resolve(gameId: Long, platform: PlatformSlug, rules: LaunchRules) =
    withContext(Dispatchers.IO) {
      LaunchResolver.resolve(
        LaunchSubject(gameId, platform),
        rules,
        dispatcher.installedPackages(),
      )
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

  /** A client for the save server, and the account its sync records belong to. */
  private suspend fun saveClient(request: PlayRequest): Pair<RommClient, String>? {
    request.saveServer?.let {
      return RommClient(it) to it
    }
    val signedIn = credentials.load() ?: return null
    return RommClient(signedIn.baseUrl, signedIn.token) to "${signedIn.baseUrl}\n${signedIn.token}"
  }

  /**
   * Syncs [game]'s save before it launches, asking the player through [chooseSave] when both copies
   * changed. Returns false when the player canceled the launch.
   */
  private suspend fun syncBeforeLaunch(
    request: PlayRequest,
    game: GameSave,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String, Boolean) -> Unit,
  ): Boolean {
    val (client, account) =
      saveClient(request)
        ?: run {
          note("Not signed in, so the save didn't sync", true)
          return true
        }
    client.use {
      val sync = saveSync(activity, it, account)
      try {
        when (val result = sync.sync(game)) {
          is SaveSyncResult.Conflict -> {
            val conflict =
              SaveConflict(
                request.title,
                SaveVersion(result.device.modifiedAt, result.device.sizeBytes),
                SaveVersion(result.server.updatedAt, result.server.fileSizeBytes),
              )
            val choice = chooseSave(conflict)
            if (choice == null) {
              note("Launch canceled; both saves are as they were", true)
              return false
            }
            describe(sync.resolve(game, result, choice), note)
          }
          else -> describe(result, note)
        }
      } catch (e: RommException) {
        note(syncFailed(e), false)
      } catch (e: IOException) {
        note(syncFailed(e), false)
      }
    }
    return true
  }

  /** Syncs [game]'s save after the game, which sends what it saved. */
  private suspend fun syncAfterReturn(
    request: PlayRequest,
    game: GameSave,
    note: (String, Boolean) -> Unit,
  ) {
    val (client, account) =
      saveClient(request)
        ?: run {
          note("Not signed in, so the save didn't sync", true)
          return
        }
    client.use {
      try {
        when (val result = syncLock.withLock { saveSync(activity, it, account).sync(game) }) {
          // The conflict screen comes before a launch, so the choice waits for the next one.
          is SaveSyncResult.Conflict ->
            note(
              "The save changed on RomM during the game too, so you'll choose which to keep the " +
                "next time you play.",
              false,
            )
          else -> describe(result, note)
        }
      } catch (e: RommException) {
        note(syncFailed(e), false)
      } catch (e: IOException) {
        note(syncFailed(e), false)
      }
    }
  }

  // A failed sync writes nothing, and its record stays as it was, so the next sync tries again.
  private fun syncFailed(e: Exception) =
    "Couldn't sync the save with RomM: ${e.message}. The save on this device stays as it is, and " +
      "syncs the next time you play."

  private fun describe(result: SaveSyncResult, note: (String, Boolean) -> Unit) {
    when (result) {
      SaveSyncResult.NoSave -> note("No save yet, here or on RomM", true)
      is SaveSyncResult.UpToDate -> note("The save is up to date", true)
      is SaveSyncResult.Pulled ->
        note(
          "Got the newer save from RomM." +
            if (result.previous != null) " The one this device had is in the save history." else "",
          false,
        )
      is SaveSyncResult.Pushed ->
        note("Sent ${result.file} to RomM as save ${result.save.id}", true)
      is SaveSyncResult.NoFolder ->
        note(
          "RomM has a save for this game, but the emulator hasn't made its save folder yet, so " +
            "the save stays on RomM for now.",
          false,
        )
      is SaveSyncResult.Conflict -> note("Both saves changed", true)
      is SaveSyncResult.ServerNewer -> note("RomM's save is newer", true)
      is SaveSyncResult.Resolved -> {
        val kept = if (result.kept == SaveCopy.Device) "this device's" else "RomM's"
        note("Kept $kept save; the other is in the save history at ${result.history}", true)
        result.uploadError?.let {
          note(
            "Kept $kept save. The other copy is in the save history on this device, but couldn't " +
              "go to RomM: $it",
            false,
          )
        }
      }
    }
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
