package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.composeunstyled.Text
import dev.sargunv.handheld.data.achievements.RommAchievementProgress
import dev.sargunv.handheld.data.achievements.achievementProgress
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSave
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.NOT_ON_SERVER
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.saves.ago
import dev.sargunv.handheld.ui.saves.size
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    log.w(e) { "Reading the save history of ROM $romId failed" }
    SaveHistoryState.Failed(labelled("Saves unavailable", e.message))
  }

/**
 * A save on the server, as a game's save history lists it.
 *
 * @param device the device that made it, or null when the server doesn't say.
 * @param emulator the emulator it belongs to, or null when the server doesn't say.
 */
@Immutable
data class ServerSave(
  val device: String?,
  val emulator: String?,
  val changedAt: Instant,
  val sizeBytes: Long,
)

/** The name that stands for this device in a save history. */
internal const val THIS_DEVICE = "This device"

/**
 * The server's [saves] of one game, newest first, naming each one's device: [THIS_DEVICE] when it
 * came from [thisDevice] (the server's id for this device), else the name the server gives it.
 */
internal fun serverSaveHistory(saves: List<RommSave>, thisDevice: String?): List<ServerSave> =
  saves.sortedWith(compareByDescending<RommSave> { it.updatedAt }.thenByDescending { it.id }).map {
    save ->
    val origin = save.originDeviceId
    val device =
      when {
        origin == null -> null
        origin == thisDevice -> THIS_DEVICE
        else -> save.deviceSyncs.find { it.deviceId == origin }?.deviceName
      }
    ServerSave(device, save.emulator, save.updatedAt, save.fileSizeBytes)
  }

private val log = Logger.withTag("GameSecondary")
