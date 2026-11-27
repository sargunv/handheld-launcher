package dev.sargunv.handheld.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.focusItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** An achievement of the running game, as the quick menu lists it. */
@Immutable
data class AchievementItem(
  val id: Long,
  val title: String,
  val description: String,
  val points: Int,
  val unlocked: Boolean,
  /** How far the player has got, such as `3/10`, for a locked achievement that measures it. */
  val progress: String? = null,
)

/** Where the running game's achievements stand. */
@Immutable
sealed interface GameAchievements {
  /** The game doesn't take part: the device isn't signed in, or the platform has none. */
  data object Off : GameAchievements

  data object Loading : GameAchievements

  /** RetroAchievements doesn't know the game, or has no achievements for it. */
  data object NoSet : GameAchievements

  data class Failed(val reason: String) : GameAchievements

  data class Ready(val game: String, val hardcore: Boolean, val items: List<AchievementItem>) :
    GameAchievements {
    val unlocked: Int
      get() = items.count { it.unlocked }
  }
}

/** What the quick menu's Achievements entry says. */
internal fun GameAchievements.summary(): String =
  when (this) {
    GameAchievements.Off -> "Off"
    GameAchievements.Loading -> "Loading"
    GameAchievements.NoSet -> "None"
    is GameAchievements.Failed -> "Unavailable"
    is GameAchievements.Ready -> "$unlocked of ${items.size}"
  }

/** The quick menu's list of the game's achievements, unlocked and locked. */
@Composable
internal fun AchievementList(achievements: GameAchievements) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Achievements",
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
    val note =
      when (achievements) {
        GameAchievements.Off -> "Off · Settings → RetroAchievements"
        GameAchievements.Loading -> "Loading"
        GameAchievements.NoSet -> "No achievements"
        is GameAchievements.Failed -> achievements.reason
        is GameAchievements.Ready -> {
          val mode = if (achievements.hardcore) " · Hardcore" else ""
          "${achievements.game} · ${achievements.unlocked} of ${achievements.items.size} " +
            "unlocked$mode"
        }
      }
    Text(
      note,
      color = Placeholder.mutedText,
      modifier =
        Modifier.testTag("achievements:note").semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (achievements is GameAchievements.Ready) {
      Column(
        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        for (item in achievements.items) AchievementRow(item)
      }
    }
  }
}

@Composable
private fun AchievementRow(item: AchievementItem) {
  val state =
    when {
      item.unlocked -> "Unlocked"
      item.progress != null -> item.progress
      else -> "Locked"
    }
  Row(
    Modifier.focusItem("achievement:${item.id}", stateDescription = state)
      .fillMaxWidth()
      .background(Placeholder.tile)
      .padding(horizontal = 20.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(item.title, color = if (item.unlocked) Placeholder.text else Placeholder.mutedText)
      Text(
        "${item.description} · ${points(item.points)}",
        color = Placeholder.mutedText,
        fontSize = 14.sp,
      )
    }
    Text(state, color = if (item.unlocked) Placeholder.text else Placeholder.mutedText)
  }
}

private fun points(points: Int) = if (points == 1) "1 point" else "$points points"

/** A short message over the game, such as an unlocked achievement. */
@Immutable data class Toast(val title: String, val detail: String? = null)

/** The toasts waiting to show over the game, one at a time, each for [duration]. */
@Stable
class ToastState(internal val duration: Duration = 4.seconds) {
  private val queue = mutableStateListOf<Toast>()

  /** The toast showing now, or null. */
  val current: Toast?
    get() = queue.firstOrNull()

  fun show(toast: Toast) {
    queue += toast
  }

  internal fun dismiss(toast: Toast) {
    if (queue.firstOrNull() == toast) queue.removeAt(0)
  }
}

/** The toast for an unlocked achievement. */
fun unlockedToast(title: String, points: Int): Toast =
  Toast("Achievement unlocked: $title", points(points))

/**
 * Shows [state]'s toasts at the top of the screen, quietly: a small neutral panel that comes and
 * goes without taking focus.
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
  val toast = state.current ?: return
  LaunchedEffect(toast) {
    delay(state.duration)
    state.dismiss(toast)
  }
  Box(modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
    Column(
      Modifier.widthIn(max = 560.dp)
        .testTag("toast")
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
        .background(Placeholder.dialog)
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(toast.title, color = Placeholder.text)
      toast.detail?.let { Text(it, color = Placeholder.mutedText, fontSize = 14.sp) }
    }
  }
}
