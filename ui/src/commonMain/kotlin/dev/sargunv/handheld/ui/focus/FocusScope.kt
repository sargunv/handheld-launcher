package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged

/**
 * The focus scopes on screen, in the order they appeared. The last one is the active one: the
 * screen on top, or the dialog over it. Only the active scope holds focus. A scope that appears
 * while a dialog is open goes below the dialog, unless it's inside it, so a screen recomposing
 * under a dialog doesn't take focus from it.
 */
@Stable
class FocusScopes internal constructor() {
  private val stack = mutableStateListOf<FocusScopeState>()

  internal val active: FocusScopeState?
    get() = stack.lastOrNull()

  internal fun add(scope: FocusScopeState) {
    val dialog = stack.indexOfFirst { it.isModal && !scope.isInside(it) }
    if (dialog < 0) stack.add(scope) else stack.add(dialog, scope)
  }

  internal fun remove(scope: FocusScopeState) {
    val wasActive = active === scope
    stack.remove(scope)
    if (wasActive) active?.restorePending = true
  }
}

internal val LocalFocusScopes = staticCompositionLocalOf<FocusScopes?> { null }

/**
 * Whether a dialog holds focus, so what's behind it shouldn't act, even for a screen reader, which
 * can reach it.
 */
@Composable internal fun isDialogOpen(): Boolean = LocalFocusScopes.current?.isDialogOpen == true

/** Whether a dialog holds focus. Reading this observes it. */
internal val FocusScopes.isDialogOpen: Boolean
  get() = active?.isModal == true

internal val LocalFocusScope = staticCompositionLocalOf<FocusScopeState?> { null }

/** Holds the [FocusScope]s below it. One lives at the root of each window's content. */
@Composable
fun FocusRoot(content: @Composable () -> Unit) {
  val scopes = remember { FocusScopes() }
  CompositionLocalProvider(LocalFocusScopes provides scopes, content = content)
}

/**
 * Remembers which of a region's items had focus last, and how to find them again.
 *
 * @param isRecording whether focus landing on an item now is the player's doing, and worth
 *   remembering.
 */
@Stable
internal class FocusMemory(
  private val saved: MutableState<String?>,
  private val isRecording: () -> Boolean,
) {
  private val items = mutableStateMapOf<String, FocusRequester>()

  /** Whether no items are composed. Reading this observes it. */
  val isEmpty: Boolean
    get() = items.isEmpty()

  val lastFocusedKey: String?
    get() = saved.value

  fun register(key: String, requester: FocusRequester) {
    items[key] = requester
  }

  fun unregister(key: String, requester: FocusRequester) {
    if (items[key] === requester) items.remove(key)
  }

  fun onItemFocused(key: String) {
    if (isRecording()) saved.value = key
  }

  /** Makes [key] the remembered item, whether or not it's composed. */
  fun select(key: String) {
    saved.value = key
  }

  /** Focuses the item that had focus last, if it's still composed. */
  fun focusRemembered(): Boolean =
    saved.value?.let(items::get)?.requestFocus(FocusDirection.Enter) ?: false
}

internal val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** The state of a [FocusScope]. */
@Stable
class FocusScopeState
internal constructor(
  saved: MutableState<String?>,
  private val scopes: FocusScopes?,
  private val parent: FocusScopeState? = null,
  internal val isModal: Boolean = false,
) {
  internal val memory = FocusMemory(saved) { isRecording }
  internal val group = FocusRequester()
  internal var hasFocus by mutableStateOf(false)

  /**
   * Whether this scope became active and hasn't restored focus yet. Focus that lands in the
   * meantime isn't the player's doing: when the screen or dialog above goes away, Android hands
   * focus to the first item before this scope restores it.
   */
  internal var restorePending = true

  /** Whether focus landing on an item in this scope now is worth remembering. */
  internal val isRecording: Boolean
    get() = isActive && !restorePending

  internal fun isInside(other: FocusScopeState): Boolean =
    generateSequence(parent) { it.parent }.any { it === other }

  /** Whether this is the newest scope on screen, the one that holds focus. */
  val isActive: Boolean
    get() = scopes == null || scopes.active === this

  /** The key of the item that had focus last, or of the initial item before anything had focus. */
  val lastFocusedKey: String?
    get() = memory.lastFocusedKey

  /**
   * Puts focus back in this scope: on the item that had it last if that item is still composed,
   * otherwise on the first item, or on the scope itself when it has no items.
   */
  fun restore(): Boolean {
    restorePending = false
    return memory.focusRemembered() || group.requestFocus(FocusDirection.Enter)
  }

  /**
   * Moves focus to the item with [key], such as the target of a jump, and remembers it as the item
   * to restore. Returns false if that item isn't composed.
   */
  fun focus(key: String): Boolean {
    memory.select(key)
    restorePending = false
    return memory.focusRemembered()
  }
}

/**
 * A region that owns focus while it's active, such as a screen or a dialog, and gets it back when
 * it becomes active again.
 *
 * Every [focusItem] inside reports to the nearest scope. When this scope becomes the newest one on
 * screen (it appeared, or the screen or dialog above it went away), or while it's the newest and
 * nothing inside has focus (the focused item went away), focus returns to the item that had it
 * last, or [initialFocus], or the first item. The remembered item survives this scope leaving
 * composition the way [rememberSaveable] state does.
 *
 * A scope with no items, such as an empty or loading screen, holds focus itself, so actions like
 * [Action.Back][dev.sargunv.handheld.ui.input.Action.Back] still reach it and its ancestors.
 */
@Composable
fun FocusScope(
  modifier: Modifier = Modifier,
  initialFocus: String? = null,
  content: @Composable BoxScope.() -> Unit,
) = FocusScope(modifier, initialFocus, isModal = false, content)

/**
 * A [FocusScope] that stays above scopes appearing outside it while it's open, as a dialog does.
 */
@Composable
internal fun FocusScope(
  modifier: Modifier,
  initialFocus: String?,
  isModal: Boolean,
  content: @Composable BoxScope.() -> Unit,
) {
  val saved = rememberSaveable { mutableStateOf(initialFocus) }
  val scopes = LocalFocusScopes.current
  val parent = LocalFocusScope.current
  val state = remember(saved, scopes) { FocusScopeState(saved, scopes, parent, isModal) }

  if (scopes != null) {
    DisposableEffect(scopes, state) {
      scopes.add(state)
      onDispose { scopes.remove(state) }
    }
    val isActive by remember(state) { derivedStateOf { state.isActive } }
    // Restore on becoming active even if something here already has focus, because Android may
    // have handed it to the first item.
    LaunchedEffect(isActive) {
      if (isActive) {
        // Lazy layouts compose their items during layout, so wait for a frame to find them.
        withFrameNanos {}
        state.restore()
      }
    }
    LaunchedEffect(isActive, state.hasFocus) {
      if (isActive && !state.hasFocus) {
        withFrameNanos {}
        state.restore()
      }
    }
  }

  CompositionLocalProvider(LocalFocusScope provides state, LocalFocusMemory provides null) {
    Box(
      modifier
        .onFocusChanged { state.hasFocus = it.hasFocus }
        .focusRequester(state.group)
        // A group while it has items; otherwise a focus target itself. When items appear, the
        // scope gives up focus and the restore effect above moves it to them.
        .focusProperties { canFocus = state.memory.isEmpty }
        .focusTarget(),
      content = content,
    )
  }
}
