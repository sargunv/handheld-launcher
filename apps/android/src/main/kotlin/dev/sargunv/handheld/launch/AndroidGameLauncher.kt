package dev.sargunv.handheld.launch

import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.HandheldApp
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.firmware.FirmwareInstaller
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveLookup
import dev.sargunv.handheld.data.saves.StandaloneEmulator
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.builtInGameSave
import dev.sargunv.handheld.data.saves.gameSaveFor
import dev.sargunv.handheld.data.saves.keptSaves
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.achievements.retroAchievementsConsole
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchResolution
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.launch.RuleChain
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
import kotlinx.coroutines.withContext

/**
 * Plays games from [activity]. The rule chain picks the app. A game it gives the built-in session
 * plays in [SessionActivity], with its core from the app's installed cores. For a standalone
 * emulator, the emulator's firmware goes in place and the dispatcher starts it. Either way, a RomM
 * game's save syncs before the game and after it, and [HandheldApp.plays] owns the play from the
 * start of its launch.
 *
 * An emulator stays in memory after the player goes back to the library, and Android doesn't let
 * the launcher stop it, so each one it starts counts as running until the device shows otherwise
 * (see [Emulators]). While it may be, the launcher never pulls a save it may hold without asking:
 * after the game it only sends, and before the next launch it asks the player.
 */
internal class AndroidGameLauncher(
  private val app: HandheldApp,
  private val activity: ComponentActivity,
) : GameLauncher {
  private val dispatcher = LaunchDispatcher(activity)
  private val emulators = app.emulators

  override val canCloseEmulators: Boolean
    get() = emulators.canClose

  override val playing
    get() = app.plays.playing

  override suspend fun keptSaves(game: LibraryGame): List<KeptSave> {
    // Save sync keeps copies only of the saves it syncs, in the signed-in account's history.
    val history = app.data.romm.account?.saveHistory ?: return emptyList()
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
    app.plays.play(game.key, game.title) {
      val volumes = withContext(Dispatchers.IO) { app.storageTargets().map { it.root } }
      if (!app.allFilesAccess.value && volumes.any { File(path).startsWith(it) }) {
        app.openAllFilesAccessSettings(activity)
        return@play listOf("Needs all-files access")
      }
      val file =
        withContext(Dispatchers.IO) { launchFile(path) } ?: return@play listOf("Folder empty")
      val subject = LaunchSubject(game.key, game.platformSlug, collections)
      val resolution =
        withContext(Dispatchers.IO) {
          RuleChain.explain(subject, rules, app.apps.packageNames()).resolve()
        }
      val launch =
        Launch(
          this,
          game,
          file,
          volumes,
          onProgress,
          closeEmulators && emulators.canClose,
          askStillRunning,
          chooseSave,
        )
      when (resolution) {
        null -> launch.note(noEmulator(game.platformSlug))
        is LaunchResolution.BuiltIn -> launch.builtIn(resolution.core)
        is LaunchResolution.Standalone -> launch.standalone(resolution)
      }
      launch.notes.toList()
    }

  /**
   * The launch of [game], from [file], as [play], which gathers in [notes] what the player should
   * know about it.
   */
  private inner class Launch(
    private val play: Plays.Play,
    private val game: LibraryGame,
    private val file: String,
    private val volumes: List<String>,
    private val onProgress: (String) -> Unit,
    private val closeEmulators: Boolean,
    private val askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    private val chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ) {
    private val platform = game.platformSlug

    /** A set, since a sync that fails before and after the game says the same thing twice. */
    val notes = linkedSetOf<String>()

    fun note(text: String) {
      notes += text
      log.i { text }
    }

    /**
     * Plays the game in [core]. Its saves go in a folder of its own; its save memory syncs with
     * RomM, filed under the core, and its save states stay on this device.
     */
    suspend fun builtIn(core: LibretroCore) {
      val coreFile =
        try {
          app.cores.ensure(core) { downloaded, total ->
            val percent = total?.takeIf { it > 0 }?.let { " ${downloaded * 100 / it}%" } ?: ""
            onProgress("Downloading core$percent")
          }
        } catch (e: IOException) {
          return note(labelled("Core download failed", e.message))
        }
      val saves = app.data.builtInSaveFolders.saves
      val folder = saves.folderOf(game.key)
      val saveRam = saves.saveRamOf(folder, file)
      // Made before the sync, which pulls a save only into a folder that exists.
      withContext(Dispatchers.IO) { File(folder).mkdirs() }
      val save = game.romId?.let { builtInGameSave(it, core.library, File(saveRam)) }
      if (save != null && !app.playSaves.beforeLaunch(game.title, save, chooseSave, ::note, null)) {
        return
      }
      val settings = app.data.settings.settings.value
      val launch =
        SessionLaunch(
          core = coreFile.toString(),
          content = file,
          saveDir = folder,
          saveRam = saveRam,
          states = saves.statesOf(folder, file),
          input = settings.input,
          stackedScreensOptions = core.stackedScreensOptions,
          lowerScreenWidthShare = core.lowerScreenWidthShare,
          achievementsConsole = retroAchievementsConsole(platform),
          hardcore = settings.achievements.hardcore,
        )
      notes +=
        play.builtIn(save) { activity.startActivity(SessionActivity.intent(activity, launch)) }
    }

    /** Plays the game in the standalone emulator [resolution] picked. */
    suspend fun standalone(resolution: LaunchResolution.Standalone) {
      val target = resolution.target
      val packageName = target.packageName
      val name = appName(target)
      val command =
        target.commandFor(file, platform, app.apps.versionName(packageName))
          ?: return note("No core for this platform · $name")
      val save = lookUpSave(target)
      val probe = DeviceProbe(activity, useRoot = closeEmulators)
      if (save != null) {
        val state = emulators.records.state(packageName, probe)
        log.i { "$packageName: $state" }
        val emulator =
          StandaloneEmulator(
            state,
            askStillRunning = {
              askStillRunning(StillRunning(game.title, name, canClose = closeEmulators))
            },
            close = if (closeEmulators) ({ emulators.close(packageName) }) else null,
          )
        if (!app.playSaves.beforeLaunch(game.title, save, chooseSave, ::note, emulator)) return
      }
      // A game launches even when its firmware can't be placed: some emulators boot without it.
      volumes.firstOrNull()?.let { placeFirmware(target, it) }
      notes +=
        play.standalone(packageName, name, save, canQuit = closeEmulators) {
          // The companion goes first, so the emulator, started second, ends up with the focus.
          PlayCompanionActivity.show(activity)
          // Noted before the launch, so an emulator that starts counts as running even if the
          // launcher stops before it hears back. A launch that fails errs the same way.
          val before = emulators.records.launched(packageName, game.romId, probe)
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

    /** The game's save as [target] keeps it, or null when it doesn't sync. */
    private suspend fun lookUpSave(target: LaunchTarget): GameSave? {
      val romId = game.romId ?: return null
      val lookup =
        withContext(Dispatchers.IO) {
          gameSaveFor(target, platform, romId, file, volumes) { file, keys ->
            platform.headerPlatform()?.let { romGameId(it, file, keys) }
          }
        }
      when (lookup) {
        is SaveLookup.Found -> log.i { "The save syncs as ${lookup.save}" }
        SaveLookup.NotSupported -> log.i { "${target.packageName}'s saves don't sync yet" }
        SaveLookup.NoGameId -> note("Save won't sync · No game id in the file")
      }
      return (lookup as? SaveLookup.Found)?.save
    }

    /**
     * Puts the firmware [target]'s emulator needs for the platform where it reads it on [storage],
     * from the signed-in RomM server, noting what went wrong.
     */
    private suspend fun placeFirmware(target: LaunchTarget, storage: String) {
      val emulator = target.emulator ?: return
      val location = BiosKnowledge.locationFor(emulator.id, platform) ?: return
      val client = app.data.romm.account?.client ?: return
      val result =
        try {
          FirmwareInstaller(client, File(activity.cacheDir, "firmware"))
            .install(platform, location, location.folders(storage, target.packageName))
        } catch (e: RommException) {
          return note(labelled("BIOS list failed", e.message))
        } catch (e: IOException) {
          return note(labelled("BIOS not placed", e.message))
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

  private fun appName(target: LaunchTarget): String =
    target.emulator?.displayName
      ?: runCatching {
          val info = activity.packageManager.getApplicationInfo(target.packageName, 0)
          activity.packageManager.getApplicationLabel(info).toString()
        }
        .getOrDefault(target.packageName)
}

private val log = Logger.withTag("Play")

/** The ROM id of a RomM game, whose save syncs, or null for a local game, whose save stays here. */
private val LibraryGame.romId: Long?
  get() = (key as? GameKey.Romm)?.romId

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
