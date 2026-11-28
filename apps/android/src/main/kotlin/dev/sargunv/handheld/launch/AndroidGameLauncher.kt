package dev.sargunv.handheld.launch

import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.firmware.FirmwareInstaller
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveLookup
import dev.sargunv.handheld.data.saves.StandaloneEmulator
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.gameSaveFor
import dev.sargunv.handheld.data.saves.keptSaves
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchResolution
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.launch.RuleChain
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.BiosKnowledge
import dev.sargunv.handheld.domain.storage.GameLayout
import dev.sargunv.handheld.session.romGameId
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.noEmulator
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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
 * RomM, the emulator's firmware goes in place, and the dispatcher starts it. [plays] owns each
 * play, from the start of its launch.
 *
 * An emulator stays in memory after the player goes back to the library, and Android doesn't let
 * the launcher stop it, so each one it starts counts as running until the device shows otherwise
 * (see [Emulators]). While it may be, the launcher never pulls a save it may hold without asking:
 * after the game it only sends, and before the next launch it asks the player.
 *
 * @param allFilesAccess whether the app has all-files access, which reading a game in shared
 *   storage needs.
 * @param openAllFilesAccess opens the system page that grants it.
 * @param storageVolumes the mounted shared storage volumes' roots, the device's own first.
 */
internal class AndroidGameLauncher(
  private val activity: ComponentActivity,
  private val romm: RommSession,
  private val saves: PlaySaves,
  private val builtIn: BuiltInPlayer,
  private val emulators: Emulators,
  private val plays: Plays,
  private val allFilesAccess: StateFlow<Boolean>,
  private val openAllFilesAccess: () -> Unit,
  private val storageVolumes: () -> List<String>,
) : GameLauncher {
  private val dispatcher = LaunchDispatcher(activity)

  override val canCloseEmulators: Boolean
    get() = emulators.canClose

  override val playing: StateFlow<GameKey?>
    get() = plays.playing

  override suspend fun keptSaves(game: LibraryGame): List<KeptSave> {
    // Save sync keeps copies only of the saves it syncs, in the signed-in account's history.
    val history = romm.account?.saveHistory ?: return emptyList()
    if (game !is Rom) return emptyList()
    return withContext(Dispatchers.IO) { keptSaves(history, game.id) }
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
  ): List<String> =
    plays.play(game.key, game.title) {
      val volumes = withContext(Dispatchers.IO) { storageVolumes() }
      if (!allFilesAccess.value && volumes.any { File(path).startsWith(it) }) {
        openAllFilesAccess()
        return@play listOf("Needs all-files access")
      }
      val file =
        withContext(Dispatchers.IO) { launchFile(path) } ?: return@play listOf("Folder empty")
      val request = PlayRequest(file, game.platformSlug, game.title, game.key)
      // A set, since a sync that fails before and after the game says the same thing twice.
      val notes = linkedSetOf<String>()
      fun note(text: String) {
        notes += text
        log.i { text }
      }

      when (
        val resolution = resolve(LaunchSubject(request.game, request.platform, collections), rules)
      ) {
        null -> note(noEmulator(request.platform))
        is LaunchResolution.BuiltIn ->
          notes += builtIn.play(this, request, resolution.core, onProgress, chooseSave, ::note)
        is LaunchResolution.Standalone ->
          notes +=
            playStandalone(
              this,
              request,
              resolution,
              volumes,
              closeEmulators && emulators.canClose,
              askStillRunning,
              chooseSave,
              ::note,
            )
      }
      notes.toList()
    }

  /**
   * Plays [request]'s game as [play] in the standalone emulator [resolution] picked, telling [note]
   * what the player should know before the game and returning what they should know after it.
   */
  private suspend fun playStandalone(
    play: Plays.Play,
    request: PlayRequest,
    resolution: LaunchResolution.Standalone,
    volumes: List<String>,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
  ): List<String> {
    val target = resolution.target
    val packageName = target.packageName
    val name = appName(target)
    val command =
      target.commandFor(request.romPath, request.platform, dispatcher.versionName(packageName))
    if (command == null) {
      note("No core for this platform · $name")
      return emptyList()
    }
    val save = lookUpSave(request, target, volumes, note)
    val probe = DeviceProbe(activity, useRoot = closeEmulators)
    if (save != null) {
      val state = emulators.records.state(packageName, probe)
      log.i { "$packageName: $state" }
      val emulator =
        StandaloneEmulator(
          state,
          askStillRunning = {
            askStillRunning(StillRunning(request.title, name, canClose = closeEmulators))
          },
          close = if (closeEmulators) ({ emulators.close(packageName) }) else null,
        )
      val synced = saves.beforeLaunch(request.title, save, chooseSave, note, emulator)
      if (!synced) return emptyList()
    }
    // A game launches even when its firmware can't be placed: some emulators boot without it.
    volumes.firstOrNull()?.let { placeFirmware(target, request.platform, it, note) }
    return play.standalone(packageName, name, save, canQuit = closeEmulators) { companion ->
      // The companion goes first, so the emulator, started second, ends up with the focus.
      PlayCompanion.show(activity, companion)
      // Noted before the launch, so an emulator that starts counts as running even if the launcher
      // stops before it hears back. A launch that fails errs the same way.
      val before =
        emulators.records.launched(packageName, (request.game as? GameKey.Romm)?.romId, probe)
      dispatcher.launch(command).also { started ->
        if (started) {
          log.i { "Started $packageName (${resolution.rule})" }
        } else {
          // Nothing started, so the emulator still holds whatever game it held.
          emulators.records.launchFailed(packageName, before)
          note("Launch failed · $name")
        }
      }
    }
  }

  /** [request]'s save as [target] keeps it, or null when it doesn't sync. */
  private suspend fun lookUpSave(
    request: PlayRequest,
    target: LaunchTarget,
    volumes: List<String>,
    note: (String) -> Unit,
  ): GameSave? {
    val romId = (request.game as? GameKey.Romm)?.romId ?: return null
    val lookup =
      withContext(Dispatchers.IO) {
        gameSaveFor(target, request.platform, romId, request.romPath, volumes) { file, keys ->
          request.platform.headerPlatform()?.let { romGameId(it, file, keys) }
        }
      }
    when (lookup) {
      is SaveLookup.Found -> log.i { "The save syncs as ${lookup.save}" }
      SaveLookup.NotSupported -> log.i { "${target.packageName}'s saves don't sync yet" }
      SaveLookup.NoGameId -> note("Save won't sync · No game id in the file")
    }
    return (lookup as? SaveLookup.Found)?.save
  }

  private suspend fun resolve(subject: LaunchSubject, rules: LaunchRules) =
    withContext(Dispatchers.IO) {
      RuleChain.explain(subject, rules, dispatcher.installedPackages()).resolve()
    }

  private fun appName(target: LaunchTarget): String =
    target.emulator?.displayName
      ?: runCatching {
          val info = activity.packageManager.getApplicationInfo(target.packageName, 0)
          activity.packageManager.getApplicationLabel(info).toString()
        }
        .getOrDefault(target.packageName)

  /**
   * Puts the firmware [target]'s emulator needs for [platform] where it reads it on [storage], from
   * the signed-in RomM server, noting what went wrong.
   */
  private suspend fun placeFirmware(
    target: LaunchTarget,
    platform: PlatformSlug,
    storage: String,
    note: (String) -> Unit,
  ) {
    val emulator = target.emulator ?: return
    val location = BiosKnowledge.locationFor(emulator.id, platform) ?: return
    val client = romm.account?.client ?: return
    val result =
      try {
        FirmwareInstaller(client, File(activity.cacheDir, "firmware"))
          .install(platform, location, location.folders(storage, target.packageName))
      } catch (e: RommException) {
        note(labelled("BIOS list failed", e.message))
        return
      } catch (e: IOException) {
        note(labelled("BIOS not placed", e.message))
        return
      }
    log.i { "Firmware for $platform: $result" }
    when (result) {
      is FirmwareInstall.Unreachable ->
        note("BIOS not placed · Can't write ${result.folders.joinToString()}")
      is FirmwareInstall.Done ->
        for ((name, reason) in result.failed) note("BIOS not placed · $name: $reason")
      FirmwareInstall.NoneOnServer -> Unit
    }
  }
}

private val log = Logger.withTag("Play")

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
