package dev.sargunv.handheld

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.sargunv.handheld.data.auth.KeystoreCredentialStore
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchResolution
import dev.sargunv.handheld.domain.launch.LaunchResolver
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.launch.LaunchDispatcher
import dev.sargunv.handheld.launch.LaunchOutcome
import dev.sargunv.handheld.launch.gameSaveFor
import dev.sargunv.handheld.launch.installFirmwareFor
import dev.sargunv.handheld.launch.saveSync
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveConflictScreen
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Where a launch's save syncs, for [LaunchTestScreen]. */
internal class SaveSyncOptions(
  /** The game's ROM id on the server. */
  val romId: Long,
  /** A folder that stands in for the emulator's save folders, or null for its own. */
  val saveDir: String?,
  /** A server to sync with instead of the signed-in one, used without a token. */
  val serverUrl: String?,
)

/** A conflict waiting for the player's choice, or null for none. */
private class PendingConflict(
  val conflict: SaveConflict,
  val choice: CompletableDeferred<SaveCopy?> = CompletableDeferred(),
)

/**
 * Launches [rom], a game for [platform], the way the Game screen will: the resolver picks the app,
 * the game's save syncs with RomM, the emulator's firmware is put in place, and the dispatcher
 * starts it. When the launcher comes back to the front, the save syncs again, which pushes what the
 * game saved. [app], when given, is the game's own override, standing in for a rule the user would
 * set. [biosDir], when given, takes the firmware instead of the emulator's own folder. [saves],
 * when given, turns on save sync.
 */
@Composable
internal fun LaunchTestScreen(
  activity: Activity,
  rom: String?,
  platform: String?,
  app: String?,
  biosDir: String?,
  saves: SaveSyncOptions?,
) {
  var status by remember { mutableStateOf("Launching") }
  var pending by remember { mutableStateOf<PendingConflict?>(null) }
  // The save to sync when the launcher comes back from the game it started.
  var awaitingReturn by remember { mutableStateOf<GameSave?>(null) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(rom, platform, app, biosDir) {
    if (rom == null || platform == null) {
      status = "Pass the game with --es rom <path> --es platform <slug>"
      return@LaunchedEffect
    }
    val dispatcher = LaunchDispatcher(activity)
    val gameId = 0L
    val rules =
      LaunchRules(gameOverrides = app?.let { mapOf(gameId to LaunchChoice(it)) }.orEmpty())
    val subject = LaunchSubject(gameId, PlatformSlug(platform))
    status =
      when (
        val resolution = LaunchResolver.resolve(subject, rules, dispatcher.installedPackages())
      ) {
        is LaunchResolution.NoEmulatorInstalled ->
          "No emulator for $platform is installed. Try " +
            resolution.suggestions.joinToString { it.displayName }
        is LaunchResolution.Resolved -> {
          val target = resolution.target
          val input =
            LaunchInput(
              rom,
              subject.platform,
              installedVersionName = dispatcher.versionName(target.packageName),
            )
          when (val build = target.commandFor(input)) {
            is LaunchBuild.MissingCore -> "No libretro core for $platform"
            is LaunchBuild.Ready -> {
              val game = saves?.let {
                val folders = it.saveDir?.let(::listOf)
                gameSaveFor(activity, target, subject.platform, it.romId, rom, folders)
              }
              val saveStatus =
                when {
                  saves == null -> null
                  game == null -> "${target.packageName}'s saves can't sync yet"
                  else ->
                    syncBeforeLaunch(activity, saves, game, rom) { pending = it }
                      ?: run {
                        status = "Launch canceled; both saves are as they were"
                        return@LaunchedEffect
                      }
                }
              // A game launches even when its firmware can't be placed, since some emulators boot
              // without it.
              val firmware = firmwareStatus(activity, target, subject.platform, biosDir)
              val launched =
                when (val outcome = dispatcher.launch(build.command)) {
                  is LaunchOutcome.Started -> {
                    awaitingReturn = game
                    "Started ${target.packageName} (${resolution.rule}) by ${outcome.method}"
                  }
                  LaunchOutcome.NeedsAllFilesAccess -> {
                    activity.startActivity(dispatcher.allFilesAccessSettings())
                    "Grant all-files access, then launch again"
                  }
                  is LaunchOutcome.Failed -> outcome.reason
                }
              listOfNotNull(saveStatus, firmware, launched).joinToString("\n")
            }
          }
        }
      }
    Log.i("LaunchTest", status)
  }

  // The emulator can't be watched for quitting, so the game counts as over when the launcher is
  // back in front after leaving it.
  DisposableEffect(activity) {
    val lifecycle = (activity as ComponentActivity).lifecycle
    var left = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> left = true
        Lifecycle.Event.ON_RESUME -> {
          val game = awaitingReturn
          if (left && game != null && saves != null) {
            awaitingReturn = null
            scope.launch {
              val after = syncAfterReturn(activity, saves, game)
              status = "$status\nBack from the game: $after"
              Log.i("LaunchTest", status)
            }
          }
          left = false
        }
        else -> {}
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  val shown = pending
  if (shown != null) {
    val heldKeys = remember { HeldKeys() }
    ActionInputRoot(
      InputSettings(),
      heldKeys,
      Modifier.onPreviewKeyEvent {
        heldKeys.track(it)
        false
      },
    ) {
      FocusRoot {
        SaveConflictScreen(
          shown.conflict,
          onChoose = { shown.choice.complete(it) },
          onCancel = { shown.choice.complete(null) },
        )
      }
    }
    return
  }
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    BasicText(
      status,
      Modifier.align(Alignment.Center).padding(16.dp),
      style = TextStyle(color = Color.White),
    )
  }
}

private suspend fun client(activity: Activity, saves: SaveSyncOptions): RommClient? {
  if (saves.serverUrl != null) return RommClient(saves.serverUrl)
  val signedIn =
    KeystoreCredentialStore(File(activity.filesDir, "credentials")).load() ?: return null
  return RommClient(signedIn.baseUrl, signedIn.token)
}

/**
 * Syncs [game]'s save before it launches, asking the player through [showConflict] when both copies
 * changed. Returns what happened in words, or null when the player canceled the launch.
 */
private suspend fun syncBeforeLaunch(
  activity: Activity,
  saves: SaveSyncOptions,
  game: GameSave,
  rom: String,
  showConflict: (PendingConflict?) -> Unit,
): String? {
  val client = client(activity, saves) ?: return "Not signed in, so the save didn't sync"
  return client.use {
    val sync = saveSync(activity, it)
    try {
        when (val result = sync.sync(game)) {
          is SaveSyncResult.Conflict -> {
            val name = rom.substringAfterLast('/').substringBeforeLast('.')
            val pending =
              PendingConflict(
                SaveConflict(
                  name,
                  SaveVersion(result.device.modifiedAt, result.device.sizeBytes),
                  SaveVersion(result.server.updatedAt, result.server.fileSizeBytes),
                ),
              )
            Log.i("SaveSync", "Conflict: $result")
            showConflict(pending)
            val choice = pending.choice.await()
            showConflict(null)
            if (choice == null) return null
            describe(sync.resolve(game, result, choice))
          }
          else -> describe(result)
        }
      } catch (e: RommException) {
        "Couldn't sync the save: ${e.message}"
      } catch (e: IOException) {
        "Couldn't sync the save: ${e.message}"
      }
      .also { Log.i("SaveSync", "Before launch: $it") }
  }
}

/** Syncs [game]'s save after the game, which pushes what it saved. */
private suspend fun syncAfterReturn(
  activity: Activity,
  saves: SaveSyncOptions,
  game: GameSave,
): String {
  val client = client(activity, saves) ?: return "Not signed in, so the save didn't sync"
  return client.use {
    try {
        when (val result = saveSync(activity, it).sync(game)) {
          // The conflict screen comes before a launch, so the choice waits for the next one.
          is SaveSyncResult.Conflict ->
            "The save changed on RomM during the game too, so you'll choose one next launch"
          else -> describe(result)
        }
      } catch (e: RommException) {
        "Couldn't sync the save: ${e.message}"
      } catch (e: IOException) {
        "Couldn't sync the save: ${e.message}"
      }
      .also { Log.i("SaveSync", "After return: $it") }
  }
}

private fun describe(result: SaveSyncResult): String =
  when (result) {
    SaveSyncResult.NoSave -> "No save yet, here or on RomM"
    is SaveSyncResult.UpToDate -> "The save is up to date"
    is SaveSyncResult.Pulled ->
      "Got the save from RomM (save ${result.save.id}) into ${result.file}" +
        (result.previous?.let { "; the old one is kept at $it" } ?: "")
    is SaveSyncResult.Pushed ->
      "Sent ${result.file} to RomM as save ${result.save.id} in slot ${result.save.slot}"
    is SaveSyncResult.NoFolder ->
      "RomM has a save, but there's no save folder to put it in: ${result.folders.joinToString()}"
    is SaveSyncResult.Conflict -> "Both saves changed"
    is SaveSyncResult.Resolved ->
      buildString {
        append("Kept the ${if (result.kept == SaveCopy.Device) "device's" else "RomM"} save; ")
        append("the other is kept at ${result.history}")
        result.otherOnServer?.let { append(" and on RomM as save ${it.id}") }
        result.uploadError?.let { append(", but couldn't go to RomM: $it") }
      }
  }

/** What putting [target]'s firmware for [platform] in place did, or null when it needs none. */
private suspend fun firmwareStatus(
  activity: Activity,
  target: LaunchTarget,
  platform: PlatformSlug,
  biosDir: String?,
): String? {
  val credentials = KeystoreCredentialStore(File(activity.filesDir, "credentials"))
  val result =
    try {
      installFirmwareFor(activity, credentials, target, platform, biosDir?.let(::listOf))
    } catch (e: RommException) {
      return "Couldn't list the firmware: ${e.message}"
    } catch (e: IOException) {
      return "Couldn't place the firmware: ${e.message}"
    }
  return when (result) {
    null -> null
    FirmwareInstall.NoneOnServer -> "RomM has no firmware for $platform"
    is FirmwareInstall.Unreachable -> "Can't write firmware to ${result.folders.joinToString()}"
    is FirmwareInstall.Done ->
      buildString {
        append("Firmware in ${result.folder}")
        if (result.placed.isNotEmpty()) append("\nPlaced ${result.placed.joinToString()}")
        if (result.kept.isNotEmpty()) append("\nAlready there ${result.kept.joinToString()}")
        for ((name, reason) in result.failed) append("\nFailed $name: $reason")
      }
  }
}
