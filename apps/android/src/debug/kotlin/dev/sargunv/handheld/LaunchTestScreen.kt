package dev.sargunv.handheld

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.launch.PlayRequest
import dev.sargunv.handheld.launch.StandalonePlayer
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveConflictScreen
import kotlinx.coroutines.CompletableDeferred

/** Where a launch's save syncs, for [LaunchTestScreen]. */
internal class SaveSyncOptions(
  /** The game's ROM id on the server. */
  val romId: Long,
  /** A folder that stands in for the emulator's save folders, or null for its own. */
  val saveDir: String?,
  /** A server to sync with instead of the signed-in one, used without a token. */
  val serverUrl: String?,
)

/** A conflict waiting for the player's choice. */
private class PendingConflict(
  val conflict: SaveConflict,
  val choice: CompletableDeferred<SaveCopy?> = CompletableDeferred(),
)

/**
 * Launches [rom], a game for [platform], the way the Game screen does, through [StandalonePlayer]:
 * the resolver picks the app, the game's save syncs with RomM, the emulator's firmware is put in
 * place, and the dispatcher starts it. When the launcher comes back to the front, the save syncs
 * again. [app], when given, is the game's own override, standing in for a rule the user would set.
 * [biosDir], when given, takes the firmware instead of the emulator's own folder. [saves], when
 * given, turns on save sync. The screen shows every note on what happened, including the details
 * the Game screen leaves out. With [findSaveOnly], it only shows where the game's save is, and
 * neither syncs nor launches. With [syncOnly], it syncs the save as before a launch, and doesn't
 * launch.
 */
@Composable
internal fun LaunchTestScreen(
  activity: ComponentActivity,
  rom: String?,
  platform: String?,
  app: String?,
  biosDir: String?,
  saves: SaveSyncOptions?,
  findSaveOnly: Boolean = false,
  syncOnly: Boolean = false,
) {
  var status by remember { mutableStateOf("Launching") }
  var pending by remember { mutableStateOf<PendingConflict?>(null) }

  LaunchedEffect(rom, platform, app, biosDir) {
    if (rom == null || platform == null) {
      status = "Pass the game with --es rom <path> --es platform <slug>"
      return@LaunchedEffect
    }
    // The game is named by its file, as a local game is.
    val game: GameKey = GameKey.Local(rom)
    val request =
      PlayRequest(
        romPath = rom,
        platform = PlatformSlug(platform),
        title = rom.substringAfterLast('/').substringBeforeLast('.'),
        game = game,
        romId = saves?.romId,
        rules = LaunchRules(gameOverrides = app?.let { mapOf(game to LaunchChoice(it)) }.orEmpty()),
        saveFolders = saves?.saveDir?.let(::listOf),
        biosFolders = biosDir?.let(::listOf),
        saveServer = saves?.serverUrl,
      )
    val player = StandalonePlayer(activity, credentialStore(activity))
    if (findSaveOnly) {
      status = player.findSave(request).joinToString("\n") { it.text }
      return@LaunchedEffect
    }
    val choose: suspend (SaveConflict) -> SaveCopy? = { conflict ->
      val shown = PendingConflict(conflict)
      pending = shown
      try {
        shown.choice.await()
      } finally {
        pending = null
      }
    }
    val notes =
      if (syncOnly) player.syncOnly(request, choose) else player.play(request) { choose(it) }
    status = notes.joinToString("\n") { it.text }
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
