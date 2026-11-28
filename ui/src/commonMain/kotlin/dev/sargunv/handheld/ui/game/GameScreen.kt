package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.UploadPhase
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.downloads.downloadFailedLabel
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.NOT_ON_SERVER
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.saves.SaveConflictScreen
import dev.sargunv.handheld.ui.saves.StillRunningDialog
import dev.sargunv.handheld.ui.signin.AllowUploadsDialog
import kotlinx.coroutines.flow.flowOf

/** What the Game screen knows of its game. */
private sealed interface ShownGame {
  data object Loading : ShownGame

  data class Found(val game: LibraryGame) : ShownGame

  /** A local game whose file is gone. */
  data object Gone : ShownGame
}

/**
 * The game [key] names: a ROM from the library, or a local game while its file matches nothing. A
 * local game that a scan matches to a ROM shows as it was until the ROM's screen takes its place.
 */
@Composable
private fun rememberShownGame(state: AppState, key: GameKey): ShownGame {
  val shown by
    produceState<ShownGame>(ShownGame.Loading, key) {
      when (key) {
        is GameKey.Romm -> state.data.library.rom(key.romId)?.let { value = ShownGame.Found(it) }
        is GameKey.Local ->
          state.data.games.localGame(key.path).collect {
            when (it) {
              is LocalGameState.Local -> value = ShownGame.Found(it.game)
              LocalGameState.Gone -> value = ShownGame.Gone
              is LocalGameState.Matched,
              null -> Unit
            }
          }
      }
    }
  return shown
}

/**
 * A game: one primary action, focused first, that plays the game when it's on the device and
 * downloads it when it isn't. Everything else is in the menu. When [Screen.Game.play] asks, it
 * plays the game as it opens, if the game is on the device. A local game is always on the device,
 * and isn't on the server, so its saves stay on the device.
 *
 * A launch runs while the screen shows, so while one is under way, leaving the screen asks first,
 * and the menu and the main menu wait. An upload goes on when the screen goes away.
 */
@Composable
internal fun GameScreen(state: AppState, screen: Screen.Game) {
  val scope = rememberCoroutineScope()
  val actions = remember { GameActions(state, scope) }
  val shown = rememberShownGame(state, screen.key)
  val current = (shown as? ShownGame.Found)?.game
  val status by
    remember(current) {
        when (current) {
          is Rom -> state.data.downloads.status(current)
          is LocalGame -> flowOf(RomStatus.OnDevice(current.path))
          null -> flowOf(null)
        }
      }
      .collectAsState(null)
  // Whether the play the screen was opened for has started, so it starts only once.
  var autoPlayed by rememberSaveable { mutableStateOf(false) }

  val currentStatus = status
  LaunchedEffect(current, currentStatus) {
    if (!screen.play || autoPlayed || current == null || currentStatus == null) {
      return@LaunchedEffect
    }
    autoPlayed = true
    // A game that isn't on the device shows its page, where it can download.
    if (currentStatus is RomStatus.OnDevice) actions.play(current, currentStatus.path)
  }
  LaunchedEffect(actions) { actions.showUploads(screen.key) }
  // A local game removed here leaves with its file.
  LaunchedEffect(shown, actions.removedLocalGame) {
    if (shown == ShownGame.Gone && actions.removedLocalGame) state.navigator.back()
  }
  val gameBusy = state.platform.launcher.playing.collectAsState().value != null
  // The game's own play stays under way until its save has synced.
  val playing = gameBusy || screen.key in state.data.adopter.unfinished.collectAsState().value
  val latestUpload by state.data.uploads.upload.collectAsState()
  val uploading = latestUpload?.isRunning == true
  val upload = latestUpload?.takeIf { it.game.key == screen.key }
  val primary =
    when (val phase = upload?.phase) {
      is UploadPhase.Sending -> PrimaryAction.StopUpload(phase.percent)
      UploadPhase.Finishing -> PrimaryAction.Waiting("Adding to your library")
      is UploadPhase.Ended,
      null -> current?.let { primaryAction(it, status, playing, actions.playProgress) }
    }
  val hasMenu =
    current != null &&
      status != null &&
      !playing &&
      !actions.checkingUploads &&
      upload?.isRunning != true
  BackHandler(onBack = actions::back, Modifier.fillMaxSize(), enabled = gameBusy) {
    FocusScope(
      Modifier.fillMaxSize().onAction { action ->
        when {
          action == Action.Menu && hasMenu -> actions.dialog = GameDialog.Menu
          // The main menu waits for the launch, as it would leave the screen.
          action == Action.MainMenu && gameBusy -> Unit
          else -> return@onAction false
        }
        true
      },
    ) {
      val conflict = actions.saveConflict
      if (conflict != null) {
        SaveConflictScreen(
          conflict.question,
          onChoose = { conflict.answer.complete(it) },
          onCancel = { conflict.answer.complete(null) },
        )
        return@FocusScope
      }
      Column(Modifier.fillMaxSize()) {
        Row(
          Modifier.fillMaxWidth().weight(1f).padding(48.dp),
          horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
          Cover(
            (current as? Rom)?.let { it.coverLargeUrl ?: it.coverSmallUrl },
            screen.title,
            Modifier.size(240.dp, 320.dp),
            description = null,
          )
          Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
              screen.title,
              color = Placeholder.text,
              fontSize = 28.sp,
              modifier = Modifier.semantics { heading() },
            )
            current?.let { GameFacts(state, it) }
            if (shown == ShownGame.Gone) {
              Text(
                "File no longer on this device",
                color = Placeholder.mutedText,
                modifier = Modifier.testTag("note"),
              )
            }
            if (current != null && primary != null) {
              MenuButton("primary", primary.label, enabled = primary.isAction) {
                when (primary) {
                  is PrimaryAction.Download ->
                    if (current is Rom) state.data.downloads.enqueue(current)
                  PrimaryAction.Retry -> if (current is Rom) state.data.downloads.retry(current.id)
                  is PrimaryAction.Play -> actions.play(current, primary.path)
                  is PrimaryAction.StopUpload -> state.data.uploads.stop()
                  is PrimaryAction.Waiting -> Unit
                }
              }
            }
            val lines = statusLines(status)
            for ((index, line) in (lines + actions.notes).withIndex()) {
              // A screen reader says how the last thing the player did here went, such as a play,
              // and a failed download, when they appear. Progress changes too often to announce.
              val announce = status is RomStatus.Failed || index >= lines.size
              Text(
                line,
                color = Placeholder.mutedText,
                modifier =
                  Modifier.testTag("note").semantics {
                    if (announce) liveRegion = LiveRegionMode.Polite
                  },
              )
            }
            (current as? Rom)?.summary?.let {
              Text(
                it,
                color = Placeholder.mutedText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
        HintBar(
          buildList {
            if (primary?.isAction == true) add(Hint(Action.Confirm, primary.hint))
            add(Hint(Action.Back, "Back"))
            if (hasMenu) add(Hint(Action.Menu, "Menu"))
          },
          mainMenu = !gameBusy,
        )
      }
      val close = { actions.dialog = null }
      when (val dialog = actions.dialog) {
        GameDialog.Menu ->
          if (current != null) {
            GameMenu(
              current,
              status,
              uploading,
              onDismissRequest = close,
              onCancelDownload = {
                close()
                if (current is Rom) state.data.downloads.cancel(current.id)
              },
              onRemove = { actions.dialog = GameDialog.Remove },
              onUpload = {
                close()
                actions.askToUpload()
              },
              onOpenWith = {
                close()
                state.navigator.push(
                  Screen.OpenWith(OpenWithScope.Game(current.key), current.title),
                )
              },
            )
          }
        GameDialog.Remove ->
          if (current != null) {
            RemoveDialog(current, close) {
              close()
              actions.remove(current)
            }
          }
        is GameDialog.Upload ->
          if (current is LocalGame) {
            UploadDialog(current, close) {
              close()
              actions.upload(current, dialog.access)
            }
          }
        is GameDialog.AllowUploads ->
          AllowUploadsDialog(
            dialog.reason,
            onSignInAgain = {
              close()
              state.signOut(allowUploads = true, keepQueue = true)
            },
            onDismissRequest = close,
          )
        GameDialog.Leave ->
          LeaveDialog(
            screen.title,
            onStay = close,
            onLeave = {
              close()
              state.navigator.back()
            },
          )
        null -> Unit
      }
      actions.stillRunning?.let { asking ->
        StillRunningDialog(asking.question, onChoose = { asking.answer.complete(it) })
      }
    }
  }
}

/**
 * The game's platform, release year and genres, on one line. A local game has its platform, its
 * size, and that it isn't on the server.
 */
@Composable
private fun GameFacts(state: AppState, game: LibraryGame) {
  val platforms by
    remember(state.data.library) { state.data.library.platforms() }.collectAsState(emptyList())
  val platform = platforms.find { it.id == game.platformId }?.name ?: game.platformSlug.value
  val facts =
    when (game) {
      // An ISO instant starts with its year.
      is Rom -> listOfNotNull(platform, game.releaseDate?.toString()?.take(4)) + game.genres.take(2)
      is LocalGame ->
        listOf(
          platform,
          formatSize(game.sizeBytes),
          NOT_ON_SERVER.replaceFirstChar { it.uppercase() },
        )
    }
  Text(facts.joinToString(" · "), color = Placeholder.mutedText)
}

/** The collections [game] is in, as launch rules name them. A local game is in none. */
internal suspend fun collectionsOf(data: HandheldData, game: LibraryGame) =
  when (game) {
    is Rom -> data.library.collectionsOf(game.id).mapTo(mutableSetOf()) { it.launchId }
    is LocalGame -> emptySet()
  }

/** What the primary action does, by the game's [RomStatus]. */
internal sealed interface PrimaryAction {
  val label: String

  /** What the hint bar calls it. */
  val hint: String
    get() = label

  /** Whether pressing it does something now. */
  val isAction: Boolean
    get() = true

  data class Download(val sizeBytes: Long) : PrimaryAction {
    override val label = "Download (${formatSize(sizeBytes)})"
  }

  data object Retry : PrimaryAction {
    override val label = "Retry download"
  }

  data class Play(val path: String) : PrimaryAction {
    override val label = "Play"
  }

  /** The upload is sending the file, [percent] of it once the server says. */
  data class StopUpload(val percent: Int?) : PrimaryAction {
    override val label = "Uploading${percent?.let { " $it%" }.orEmpty()}"
    override val hint = "Stop upload"
  }

  /** The game is on its way or running, so the action waits. */
  data class Waiting(override val label: String) : PrimaryAction {
    override val isAction = false
  }
}

/** The primary action for [game], with [progress] saying what a launch underway is doing. */
internal fun primaryAction(
  game: LibraryGame,
  status: RomStatus?,
  playing: Boolean,
  progress: String? = null,
): PrimaryAction? =
  when (status) {
    null -> null
    RomStatus.NotOnDevice -> PrimaryAction.Download(game.sizeBytes)
    RomStatus.Queued -> PrimaryAction.Waiting("Queued to download")
    is RomStatus.Downloading -> {
      val percent =
        if (status.totalBytes > 0) " ${status.bytesDownloaded * 100 / status.totalBytes}%" else ""
      PrimaryAction.Waiting("Downloading$percent")
    }
    is RomStatus.Failed -> PrimaryAction.Retry
    is RomStatus.OnDevice ->
      if (playing) PrimaryAction.Waiting(progress ?: "Playing") else PrimaryAction.Play(status.path)
  }

/** The lines under the primary action that say how a download is going. */
private fun statusLines(status: RomStatus?): List<String> =
  when (status) {
    is RomStatus.Downloading ->
      listOf("${formatSize(status.bytesDownloaded)} of ${formatSize(status.totalBytes)}")
    is RomStatus.Failed -> listOf(downloadFailedLabel(status.error))
    else -> emptyList()
  }
