package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.Removal
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveConflictScreen
import dev.sargunv.handheld.ui.saves.ago
import dev.sargunv.handheld.ui.saves.size
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A save conflict waiting for the player's choice. */
private class PendingChoice(val conflict: SaveConflict) {
  val choice = CompletableDeferred<SaveCopy?>()
}

/**
 * A game: one primary action, focused first, that plays the game when it's on the device and
 * downloads it when it isn't. Everything else is in the menu.
 */
@Composable
internal fun GameScreen(state: AppState, screen: Screen.Game) {
  val rom by produceState<Rom?>(null, screen.romId) { value = state.library.rom(screen.romId) }
  val status by
    remember(rom) { rom?.let { state.downloads.status(it) } ?: flowOf(null) }.collectAsState(null)
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  var confirmRemove by rememberSaveable { mutableStateOf(false) }
  var playing by remember { mutableStateOf(false) }
  // What the player should know about the last thing they did here, such as how a play went.
  var notes by remember { mutableStateOf(emptyList<String>()) }
  var pending by remember { mutableStateOf<PendingChoice?>(null) }
  val scope = rememberCoroutineScope()

  fun play(rom: Rom, path: String) {
    playing = true
    notes = emptyList()
    scope.launch {
      try {
        val collections = state.library.collectionsOf(rom.id).mapTo(mutableSetOf()) { it.launchId }
        val rules = state.settings.launch.toRules()
        notes =
          state.launcher.play(rom, path, rules, collections) { conflict ->
            val choice = PendingChoice(conflict)
            pending = choice
            try {
              choice.choice.await()
            } finally {
              pending = null
            }
          }
      } finally {
        playing = false
        // The game's saves may have synced.
        state.savesChanged++
      }
    }
  }

  val current = rom
  val primary = current?.let { primaryAction(it, status, playing) }
  FocusScope(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Menu && current != null && status != null && pending == null) {
        menuOpen = true
        true
      } else {
        false
      }
    },
  ) {
    val choice = pending
    if (choice != null) {
      SaveConflictScreen(
        choice.conflict,
        onChoose = { choice.choice.complete(it) },
        onCancel = { choice.choice.complete(null) },
      )
      return@FocusScope
    }
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().weight(1f).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
      ) {
        Cover(
          current?.let { it.coverLargeUrl ?: it.coverSmallUrl },
          screen.title,
          Modifier.size(240.dp, 320.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(screen.title, color = Placeholder.text, fontSize = 28.sp)
          current?.let { GameFacts(state, it) }
          if (current != null && primary != null) {
            MenuButton("primary", primary.label) {
              when (primary) {
                is PrimaryAction.Download -> scope.launch { state.downloads.enqueue(current) }
                PrimaryAction.Retry -> scope.launch { state.downloads.retry(current.id) }
                is PrimaryAction.Play -> play(current, primary.path)
                else -> Unit
              }
            }
          }
          for (line in statusLines(status) + notes) {
            Text(line, color = Placeholder.mutedText, modifier = Modifier.testTag("note"))
          }
          current?.summary?.let {
            Text(it, color = Placeholder.mutedText, maxLines = 3, overflow = TextOverflow.Ellipsis)
          }
        }
      }
      HintBar(
        buildList {
          if (primary?.isAction == true) add(Hint(Action.Confirm, primary.label))
          add(Hint(Action.Back, "Back"))
          if (status != null) add(Hint(Action.Menu, "Menu"))
        },
      )
    }
    if (menuOpen && current != null) {
      val close = { menuOpen = false }
      Dialog(close, Modifier.background(Placeholder.dialog).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(current.title, color = Placeholder.text)
          when (status) {
            RomStatus.NotOnDevice ->
              MenuButton("menu:download", PrimaryAction.Download(current.sizeBytes).label) {
                scope.launch { state.downloads.enqueue(current) }
                close()
              }
            RomStatus.Queued,
            is RomStatus.Downloading,
            is RomStatus.Failed ->
              MenuButton("menu:cancel", "Cancel download") {
                scope.launch { state.downloads.cancel(current.id) }
                close()
              }
            is RomStatus.OnDevice ->
              MenuButton("menu:remove", "Remove from device") {
                close()
                confirmRemove = true
              }
            null -> Unit
          }
          MenuButton("menu:open-with", "Open with") {
            close()
            state.navigator.push(Screen.OpenWith(current.id, current.title))
          }
        }
      }
    }
    if (confirmRemove && current != null) {
      val close = { confirmRemove = false }
      Dialog(
        close,
        Modifier.background(Placeholder.dialog).padding(24.dp),
        initialFocus = "remove:cancel",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text("Remove ${current.title} from this device?", color = Placeholder.text)
          Text(
            "Its saves stay, here and on RomM, and you can download it again.",
            color = Placeholder.mutedText,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuButton("remove:cancel", "Cancel", close)
            MenuButton("remove:confirm", "Remove") {
              close()
              scope.launch {
                notes =
                  when (val removal = state.downloads.remove(current)) {
                    is Removal.HoldsOtherFiles ->
                      listOf(
                        "The game's folder also holds ${removal.names.joinToString()}, " +
                          "so nothing was removed.",
                      )
                    is Removal.Failed -> listOf("Couldn't remove the game: ${removal.reason}")
                    Removal.Removed,
                    Removal.NotOnDevice -> emptyList()
                  }
              }
            }
          }
        }
      }
    }
  }
}

/** The game's platform, release year and genres, on one line. */
@Composable
private fun GameFacts(state: AppState, rom: Rom) {
  val platforms by remember(state.library) { state.library.platforms() }.collectAsState(emptyList())
  val platform = platforms.find { it.id == rom.platformId }?.name ?: rom.platformSlug
  // An ISO instant starts with its year.
  val facts = listOfNotNull(platform, rom.releaseDate?.toString()?.take(4)) + rom.genres.take(2)
  Text(facts.joinToString(" · "), color = Placeholder.mutedText)
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

internal fun primaryAction(rom: Rom, status: RomStatus?, playing: Boolean): PrimaryAction? =
  when (status) {
    null -> null
    RomStatus.NotOnDevice -> PrimaryAction.Download(rom.sizeBytes)
    RomStatus.Queued -> PrimaryAction.Waiting("Queued to download")
    is RomStatus.Downloading -> {
      val percent =
        if (status.totalBytes > 0) " ${status.bytesDownloaded * 100 / status.totalBytes}%" else ""
      PrimaryAction.Waiting("Downloading$percent")
    }
    is RomStatus.Failed -> PrimaryAction.Retry
    is RomStatus.OnDevice ->
      if (playing) PrimaryAction.Waiting("Playing") else PrimaryAction.Play(status.path)
  }

/** The lines under the primary action that say how a download is going. */
private fun statusLines(status: RomStatus?): List<String> =
  when (status) {
    is RomStatus.Downloading ->
      listOf("${formatSize(status.bytesDownloaded)} of ${formatSize(status.totalBytes)}")
    is RomStatus.Failed -> listOf("The download failed: ${status.error}")
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
 * device, and where it opens.
 */
@Composable
internal fun GameSecondary(state: AppState, screen: Screen.Game) {
  val rom by produceState<Rom?>(null, screen.romId) { value = state.library.rom(screen.romId) }
  val current = rom ?: return
  val history by
    produceState<SaveHistoryState>(SaveHistoryState.Loading, current, state.savesChanged) {
      value = loadSaveHistory(state, current.id)
    }
  val kept by
    produceState(emptyList<KeptSave>(), current, state.savesChanged) {
      value = state.launcher.keptSaves(current)
    }
  val launch = state.settings.launch
  val opensIn by
    produceState<String?>(null, current, launch) {
      val collections =
        state.library.collectionsOf(current.id).mapTo(mutableSetOf()) { it.launchId }
      value = state.launcher.opensIn(current, launch.toRules(), collections)
    }
  val now = remember(history) { Clock.System.now() }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Saves", color = Placeholder.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
      when (val shown = history) {
        SaveHistoryState.Loading -> Text("Loading saves from RomM", color = Placeholder.mutedText)
        is SaveHistoryState.Failed -> Text(shown.reason, color = Placeholder.mutedText)
        is SaveHistoryState.Loaded ->
          if (shown.saves.isEmpty()) {
            Text("No saves on RomM yet.", color = Placeholder.mutedText)
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
        Text("Kept on this device", color = Placeholder.text, fontWeight = FontWeight.SemiBold)
        for (save in kept) {
          val from = if (save.copy == SaveCopy.Device) "This device's copy" else "RomM's copy"
          SaveRow(from, listOf(save.emulator, ago(save.keptAt, now), size(save.sizeBytes)))
        }
      }
    }
    opensIn?.let { Text(it, color = Placeholder.mutedText) }
  }
}

@Composable
private fun SaveRow(source: String, details: List<String>) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(source, color = Placeholder.text)
    Text(details.joinToString(" · "), color = Placeholder.mutedText)
  }
}

private suspend fun loadSaveHistory(state: AppState, romId: Long): SaveHistoryState {
  val credentials =
    state.credentials.load() ?: return SaveHistoryState.Failed("Sign in to see saves on RomM.")
  return try {
    withContext(Dispatchers.Default) {
      RommClient(credentials.baseUrl, credentials.token, state.httpEngine()).use {
        SaveHistoryState.Loaded(serverSaveHistory(it.saves(romId = romId), credentials.deviceId))
      }
    }
  } catch (e: RommException) {
    SaveHistoryState.Failed("Couldn't reach RomM for the game's saves: ${e.message}")
  }
}
