package dev.sargunv.handheld.ui.play

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.saves.ago
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A save-state slot: its number, and when its state was saved, with a picture of the game then. */
@Immutable
data class StateSlot(
  val number: Int,
  val savedAt: Instant? = null,
  val thumbnail: ImageBitmap? = null,
) {
  val isEmpty: Boolean
    get() = savedAt == null
}

/** The game session the quick menu works on. The menu calls it on its own coroutine scope. */
interface QuickMenuSession {
  /** The game's save-state slots, in order. */
  suspend fun slots(): List<StateSlot>

  /** Saves the game's state to [slot], and returns why it couldn't, in plain words, or null. */
  suspend fun saveState(slot: Int): String?

  /** Restores the state in [slot], and returns why it couldn't, in plain words, or null. */
  suspend fun loadState(slot: Int): String?

  /** Runs the game fast, or at its own speed, once it resumes. */
  fun setFastForward(on: Boolean)

  /** Where the game's achievements stand, with the list of them once they've loaded. */
  suspend fun achievements(): GameAchievements = GameAchievements.Off

  /**
   * Whether the game plays in RetroAchievements' hardcore mode, which doesn't allow loading states
   * or fast forward while the game has achievements, or may have them once they've loaded.
   */
  val hardcore: Boolean
    get() = false

  /** Closes the menu and runs the game again. */
  fun resume()

  /** Leaves the game, writing its save. */
  fun quit()
}

/** What choosing a slot in the quick menu does. */
enum class SlotAction {
  Save,
  Load,
}

/**
 * The quick menu's state, which lasts as long as the game does, so fast forward stays on between
 * openings. [open] resets it for each opening.
 */
@Stable
class QuickMenuState(private val session: QuickMenuSession, private val scope: CoroutineScope) {
  /** The save-state slots, as of the last opening or save. */
  var slots by mutableStateOf<List<StateSlot>>(emptyList())
    private set

  var fastForward by mutableStateOf(false)
    private set

  /** What choosing a slot does while the menu lists the slots, or null on the menu's main list. */
  var picking by mutableStateOf<SlotAction?>(null)
    private set

  /** The slot a pick starts on: the one saved to or loaded last, or the first. */
  var lastSlot by mutableStateOf(1)
    private set

  /** The slot focused while the menu lists them, which the lower screen marks. */
  var focusedSlot by mutableStateOf<Int?>(null)
    internal set

  /** How the last save or load went, in plain words. */
  var message by mutableStateOf<String?>(null)
    private set

  /** Whether a save or load is underway. Choosing a slot meanwhile does nothing. */
  var busy by mutableStateOf(false)
    private set

  /** The game's achievements, as of the last opening of the menu or of their list. */
  var achievements by mutableStateOf<GameAchievements>(GameAchievements.Off)
    private set

  /** Whether the menu lists the game's achievements. */
  var showsAchievements by mutableStateOf(false)
    private set

  /** Whether hardcore mode keeps the game from loading states and fast forwarding now. */
  val hardcoreRestricts: Boolean
    get() =
      session.hardcore &&
        (achievements == GameAchievements.Loading || achievements is GameAchievements.Ready)

  /** Starts an opening of the menu on its main list, and reads the slots again. */
  fun open() {
    picking = null
    focusedSlot = null
    message = null
    showsAchievements = false
    // Until they're read, hardcore assumes the game has achievements.
    if (session.hardcore) achievements = GameAchievements.Loading
    scope.launch { slots = session.slots() }
    readAchievements()
  }

  /** Lists the game's achievements, read again. */
  fun showAchievements() {
    showsAchievements = true
    message = null
    readAchievements()
  }

  /** Reads the game's achievements, and again while the session is still loading them. */
  private fun readAchievements() {
    scope.launch {
      while (true) {
        val read = session.achievements()
        achievements = read
        if (read != GameAchievements.Loading) break
        delay(ACHIEVEMENTS_POLL)
      }
    }
  }

  /** Lists the slots, for [action]. */
  fun pick(action: SlotAction) {
    picking = action
    message = null
  }

  /**
   * Goes back from the slots to the main list, or resumes from the main list. While a save or load
   * is under way, it does nothing: the game runs again once that's done.
   */
  fun back() {
    if (busy) return
    if (showsAchievements) {
      showsAchievements = false
    } else if (picking != null) {
      picking = null
      focusedSlot = null
    } else {
      session.resume()
    }
  }

  /**
   * Saves to or loads from [slot], as [picking] says. Saving goes back to the main list, which says
   * so; loading resumes the game. Loading an empty slot does nothing.
   */
  fun choose(slot: Int) {
    val action = picking ?: return
    if (busy) return
    if (action == SlotAction.Load && slots.find { it.number == slot }?.isEmpty != false) return
    if (action == SlotAction.Load && hardcoreRestricts) return
    busy = true
    scope.launch {
      try {
        when (action) {
          SlotAction.Save -> {
            val failure = session.saveState(slot)
            slots = session.slots()
            if (failure != null) {
              message = failure
            } else {
              lastSlot = slot
              message = "Saved to slot $slot."
              picking = null
              focusedSlot = null
            }
          }
          SlotAction.Load -> {
            val failure = session.loadState(slot)
            if (failure != null) {
              message = failure
            } else {
              lastSlot = slot
              session.resume()
            }
          }
        }
      } finally {
        busy = false
      }
    }
  }

  fun toggleFastForward() {
    if (!fastForward && hardcoreRestricts) return
    fastForward = !fastForward
    session.setFastForward(fastForward)
  }

  fun resume() {
    if (!busy) session.resume()
  }

  fun quit() = session.quit()
}

/**
 * The quick menu over a paused game, on the upper screen: resume, save and load state, fast forward
 * and quit. Saving and loading list the slots in the menu, and the lower screen ([StateSlotsPanel])
 * shows them with a picture of each. [Action.Back] goes back from the slots, and resumes from the
 * main list.
 */
@Composable
fun QuickMenu(state: QuickMenuState, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize()) {
    Dialog(
      onDismissRequest = state::back,
      modifier = Modifier.width(420.dp).background(Placeholder.dialog).padding(24.dp),
      initialFocus = "quick:resume",
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val action = state.picking) {
          null if state.showsAchievements -> FocusScope { AchievementList(state.achievements) }
          null -> MainList(state)
          else ->
            // A scope of its own, so the main list gets its focus back on returning.
            FocusScope(initialFocus = "slot:${state.lastSlot}") { SlotList(state, action) }
        }
        state.message?.let {
          Text(
            it,
            color = Placeholder.mutedText,
            modifier =
              Modifier.testTag("quick:message").semantics {
                liveRegion = LiveRegionMode.Polite
              },
          )
        }
      }
    }
    HintBar(
      when (state.picking) {
        null if state.showsAchievements -> listOf(Hint(Action.Back, "Back"))
        null ->
          listOf(
            Hint(Action.Confirm, "Select"),
            Hint(Action.Back, "Resume"),
          )
        SlotAction.Save -> listOf(Hint(Action.Confirm, "Save"), Hint(Action.Back, "Back"))
        SlotAction.Load -> listOf(Hint(Action.Confirm, "Load"), Hint(Action.Back, "Back"))
      },
      Modifier.align(Alignment.BottomCenter),
    )
  }
}

@Composable
private fun MainList(state: QuickMenuState) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Paused",
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
    MenuItem("quick:resume", "Resume", onConfirm = state::resume)
    MenuItem("quick:save", "Save state") { state.pick(SlotAction.Save) }
    val restricted = state.hardcoreRestricts
    MenuItem(
      "quick:load",
      "Load state",
      value = if (restricted) "Not in hardcore" else null,
      onConfirm = if (restricted) null else ({ state.pick(SlotAction.Load) }),
    )
    MenuItem(
      "quick:fast-forward",
      "Fast forward",
      value =
        when {
          restricted && !state.fastForward -> "Not in hardcore"
          state.fastForward -> "On"
          else -> "Off"
        },
      onConfirm = if (restricted && !state.fastForward) null else state::toggleFastForward,
    )
    MenuItem(
      "quick:achievements",
      "Achievements",
      value = state.achievements.summary(),
      onConfirm = state::showAchievements,
    )
    MenuItem("quick:quit", "Quit", onConfirm = state::quit)
  }
}

@Composable
private fun SlotList(state: QuickMenuState, action: SlotAction) {
  val now = Clock.System.now()
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      if (action == SlotAction.Save) "Save state" else "Load state",
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
    for (slot in state.slots) {
      val choosable = action == SlotAction.Save || !slot.isEmpty
      MenuItem(
        "slot:${slot.number}",
        "Slot ${slot.number}",
        value = slot.describe(now),
        modifier = Modifier.onFocusChanged { if (it.hasFocus) state.focusedSlot = slot.number },
        onConfirm = if (choosable) ({ state.choose(slot.number) }) else null,
      )
    }
  }
}

/** When the slot's state was saved, in words, or that it's empty. */
private fun StateSlot.describe(now: Instant): String = savedAt?.let { ago(it, now) } ?: "Empty"

/** A row of the menu: its label, and its value on the right, such as a toggle's. */
@Composable
private fun MenuItem(
  key: String,
  label: String,
  modifier: Modifier = Modifier,
  value: String? = null,
  onConfirm: (() -> Unit)?,
) {
  Row(
    modifier
      .focusItem(key, onConfirm = onConfirm, role = Role.Button)
      .fillMaxWidth()
      .background(Placeholder.tile)
      .padding(horizontal = 20.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, color = if (onConfirm != null) Placeholder.text else Placeholder.mutedText)
    if (value != null) Text(value, color = Placeholder.mutedText)
  }
}

/**
 * The lower screen while the quick menu is open: the game's save-state slots, each with a picture
 * of the game as it was saved. While the menu lists the slots, the focused one is marked and a tap
 * on one chooses it. Nothing here takes focus, so the controller stays with the menu.
 */
@Composable
fun StateSlotsPanel(state: QuickMenuState, modifier: Modifier = Modifier) {
  val now = Clock.System.now()
  Column(
    modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Text(
      "Save states",
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    for (row in state.slots.chunked(SlotColumns)) {
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (slot in row) {
          SlotTile(
            slot,
            now = now,
            marked = state.picking != null && state.focusedSlot == slot.number,
            onTap =
              state.picking?.let { action ->
                if (action == SlotAction.Save || !slot.isEmpty) ({ state.choose(slot.number) })
                else null
              },
            modifier = Modifier.weight(1f),
          )
        }
        // Keeps a short last row's tiles the size of the others.
        repeat(SlotColumns - row.size) { Box(Modifier.weight(1f)) }
      }
    }
  }
}

private const val SlotColumns = 3

@Composable
private fun SlotTile(
  slot: StateSlot,
  now: Instant,
  marked: Boolean,
  onTap: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val currentOnTap by rememberUpdatedState(onTap)
  Column(
    modifier
      .testTag("state:${slot.number}")
      .semantics(mergeDescendants = true) {
        selected = marked
        if (onTap != null) {
          role = Role.Button
          onClick {
            currentOnTap?.invoke()
            true
          }
        }
      }
      .pointerInput(Unit) { detectTapGestures { currentOnTap?.invoke() } }
      .then(if (marked) Modifier.border(3.dp, Placeholder.text) else Modifier)
      .background(Placeholder.tile)
      .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Placeholder.background),
      contentAlignment = Alignment.Center,
    ) {
      val thumbnail = slot.thumbnail
      if (thumbnail != null) {
        Image(
          thumbnail,
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
        )
      } else if (slot.isEmpty) {
        Text("Empty", color = Placeholder.mutedText)
      }
    }
    val saved = slot.savedAt?.let { " · ${ago(it, now)}" }.orEmpty()
    Text("${slot.number}$saved", color = Placeholder.text, fontSize = 16.sp)
  }
}

private val ACHIEVEMENTS_POLL = 500.milliseconds
