package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.composeunstyled.Text
import dev.sargunv.handheld.data.achievements.RommAchievementProgress
import dev.sargunv.handheld.data.achievements.achievementProgress
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
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
import dev.sargunv.handheld.ui.saves.ago
import dev.sargunv.handheld.ui.saves.size
import dev.sargunv.handheld.ui.signin.AllowUploadsDialog
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        is GameKey.Romm -> state.library.rom(key.romId)?.let { value = ShownGame.Found(it) }
        is GameKey.Local ->
          state.games.localGame(key.path).collect {
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
 * and the menu and the main menu wait.
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
          is Rom -> state.downloads.status(current)
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
  val upload = actions.upload
  val primary =
    when (upload) {
      is UploadState.Sending ->
        PrimaryAction.Waiting("Uploading${upload.percent?.let { " $it%" }.orEmpty()}")
      UploadState.Finishing -> PrimaryAction.Waiting("Adding to your library")
      UploadState.Checking,
      null -> current?.let { primaryAction(it, status, actions.playing, actions.playProgress) }
    }
  val hasMenu = current != null && status != null && !actions.playing && upload == null
  BackHandler(onBack = actions::back, Modifier.fillMaxSize(), enabled = actions.busy) {
    FocusScope(
      Modifier.fillMaxSize().onAction { action ->
        when {
          action == Action.Menu && hasMenu -> actions.dialog = GameDialog.Menu
          // The main menu waits for the launch or the upload, as it would leave the screen.
          action == Action.MainMenu && actions.busy -> Unit
          else -> return@onAction false
        }
        true
      },
    ) {
      val conflict = actions.saveConflict
      if (conflict != null) {
        SaveConflictScreen(
          conflict.conflict,
          onChoose = { conflict.choice.complete(it) },
          onCancel = { conflict.choice.complete(null) },
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
                    if (current is Rom) scope.launch { state.downloads.enqueue(current) }
                  PrimaryAction.Retry ->
                    if (current is Rom) scope.launch { state.downloads.retry(current.id) }
                  is PrimaryAction.Play -> actions.play(current, primary.path)
                  else -> Unit
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
            if (primary?.isAction == true) add(Hint(Action.Confirm, primary.label))
            when (upload) {
              is UploadState.Sending -> add(Hint(Action.Back, "Stop upload"))
              UploadState.Finishing -> Unit
              UploadState.Checking,
              null -> add(Hint(Action.Back, "Back"))
            }
            if (hasMenu) add(Hint(Action.Menu, "Menu"))
            if (!actions.busy) add(Hint(Action.MainMenu, "Main menu"))
          },
        )
      }
      val close = { actions.dialog = null }
      when (val dialog = actions.dialog) {
        GameDialog.Menu ->
          if (current != null) {
            GameMenu(
              current,
              status,
              onDismissRequest = close,
              onCancelDownload = {
                close()
                if (current is Rom) scope.launch { state.downloads.cancel(current.id) }
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
              scope.launch { state.signOut(allowUploads = true, keepQueue = true) }
            },
            onDismissRequest = close,
          )
        GameDialog.StopUpload ->
          StopUploadDialog(screen.title, onKeep = close) {
            close()
            actions.stopUpload()
          }
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
        StillRunningDialog(asking.prompt, onChoose = { asking.choice.complete(it) })
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
  val platforms by remember(state.library) { state.library.platforms() }.collectAsState(emptyList())
  val platform = platforms.find { it.id == game.platformId }?.name ?: game.platformSlug
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
internal suspend fun collectionsOf(state: AppState, game: LibraryGame) =
  when (game) {
    is Rom -> state.library.collectionsOf(game.id).mapTo(mutableSetOf()) { it.launchId }
    is LocalGame -> emptySet()
  }

/** What the primary action does, by the game's [RomStatus]. */
internal sealed interface PrimaryAction {
  val label: String

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

/** What the lower screen's save history holds. */
private sealed interface SaveHistoryState {
  data object Loading : SaveHistoryState

  data class Loaded(val saves: List<ServerSave>) : SaveHistoryState

  data class Failed(val reason: String) : SaveHistoryState
}

/**
 * The lower screen for a game: its saves on RomM across devices, the copies save sync kept on this
 * device, what the copy on this device says about itself in its header, and where it opens.
 */
@Composable
internal fun GameSecondary(state: AppState, screen: Screen.Game) {
  val game by produceState<LibraryGame?>(null, screen.key) { value = state.game(screen.key) }
  when (val current = game) {
    is Rom -> RomSecondary(state, current)
    is LocalGame -> LocalSecondary(state, current)
    null -> Unit
  }
}

/** Where [game] opens under the launch settings, in words. */
@Composable
private fun rememberOpensIn(state: AppState, game: LibraryGame): String? {
  val launch = state.settings.launch
  val opensIn by
    produceState<String?>(null, game, launch) {
      value = state.launcher.opensIn(game, launch.toRules(), collectionsOf(state, game))
    }
  return opensIn
}

/**
 * The lower screen for a local game: it isn't on the server, so its saves stay on the device, and
 * where it opens.
 */
@Composable
private fun LocalSecondary(state: AppState, game: LocalGame) {
  val opensIn = rememberOpensIn(state, game)
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      NOT_ON_SERVER.replaceFirstChar { it.uppercase() },
      color = Placeholder.text,
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("game:not-on-server").semantics { heading() },
    )
    Text(
      "Saves kept on this device",
      color = Placeholder.mutedText,
      modifier = Modifier.testTag("game:saves-local"),
    )
    Text(game.path, color = Placeholder.mutedText, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    opensIn?.let { Text(it, color = Placeholder.mutedText) }
  }
}

/**
 * The lower screen for a ROM: its saves on RomM across devices, the copies save sync kept on this
 * device, and where it opens.
 */
@Composable
private fun RomSecondary(state: AppState, current: Rom) {
  // A play may sync the game's saves, so they load again as it starts and ends.
  val playing = state.playing == current.key
  val history by
    produceState<SaveHistoryState>(SaveHistoryState.Loading, current, playing) {
      val account = state.romm.account ?: return@produceState
      value = loadSaveHistory(account, current.id)
    }
  val kept by
    produceState(emptyList<KeptSave>(), current, playing) {
      value = state.launcher.keptSaves(current)
    }
  val opensIn = rememberOpensIn(state, current)
  val achievements by
    produceState<RommAchievementProgress?>(null, current) {
      value = loadAchievementProgress(state, current.id)
    }
  val status by remember(current) { state.downloads.status(current) }.collectAsState(null)
  val path = (status as? RomStatus.OnDevice)?.path
  // A header that can't be read shows nothing.
  val header by
    produceState(emptyList<RomHeaderField>(), current, path) {
      value = path?.let { state.romHeaders.read(current, it) }.orEmpty()
    }
  val now = remember(history) { Clock.System.now() }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    achievements?.let {
      Text(
        achievementSummary(it),
        color = Placeholder.mutedText,
        modifier = Modifier.testTag("game:achievements"),
      )
    }
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "Saves",
        color = Placeholder.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      when (val shown = history) {
        SaveHistoryState.Loading -> Text("Loading saves from RomM", color = Placeholder.mutedText)
        is SaveHistoryState.Failed -> Text(shown.reason, color = Placeholder.mutedText)
        is SaveHistoryState.Loaded ->
          if (shown.saves.isEmpty()) {
            Text("No saves on RomM", color = Placeholder.mutedText)
          } else {
            for (save in shown.saves) {
              val details =
                listOfNotNull(
                  save.emulator.takeIf { save.device != null },
                  ago(save.changedAt, now),
                  size(save.sizeBytes),
                )
              SaveRow(save.device ?: save.emulator ?: "Unknown device", details)
            }
          }
      }
      if (kept.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        Text(
          "Kept on this device",
          color = Placeholder.text,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.semantics { heading() },
        )
        for (save in kept) {
          val from = if (save.copy == SaveCopy.Device) "This device's copy" else "RomM's copy"
          SaveRow(from, listOf(save.emulator, ago(save.keptAt, now), size(save.sizeBytes)))
        }
      }
      if (header.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        ThisCopy(header)
      }
    }
    opensIn?.let { Text(it, color = Placeholder.mutedText) }
  }
}

/** What the copy of the game on this device says about itself in its header. */
@Composable
private fun ThisCopy(header: List<RomHeaderField>) {
  Column(
    Modifier.testTag("game:header").semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      "This copy",
      color = Placeholder.text,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { heading() },
    )
    Text(header.joinToString(" · ") { "${it.label} ${it.value}" }, color = Placeholder.mutedText)
  }
}

@Composable
private fun SaveRow(source: String, details: List<String>) {
  Row(
    Modifier.semantics(mergeDescendants = true) {},
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(source, color = Placeholder.text)
    Text(details.joinToString(" · "), color = Placeholder.mutedText)
  }
}

/** The player's RetroAchievements progress in the game, as RomM syncs it, in words. */
internal fun achievementSummary(progress: RommAchievementProgress): String {
  val hardcore =
    if (progress.unlockedHardcore > 0) ", ${progress.unlockedHardcore} in hardcore" else ""
  return "RetroAchievements: ${progress.unlocked} of ${progress.total} unlocked$hardcore"
}

/**
 * The player's RetroAchievements progress in ROM [romId], read from RomM, or null when there's none
 * to show or RomM is out of reach. It only reads.
 */
private suspend fun loadAchievementProgress(
  state: AppState,
  romId: Long,
): RommAchievementProgress? =
  try {
    withContext(Dispatchers.Default) { state.romm.account?.client?.achievementProgress(romId) }
  } catch (e: RommException) {
    log.w(e) { "Reading the achievement progress of ROM $romId failed" }
    null
  }

private suspend fun loadSaveHistory(account: RommAccount, romId: Long): SaveHistoryState =
  try {
    withContext(Dispatchers.Default) {
      val saves = account.client.saves(romId = romId)
      SaveHistoryState.Loaded(serverSaveHistory(saves, account.credentials.deviceId))
    }
  } catch (e: RommException) {
    SaveHistoryState.Failed("Saves unavailable · ${e.message}")
  }

private val log = Logger.withTag("GameScreen")
