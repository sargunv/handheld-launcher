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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first

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

  /** Dismisses every open dialog, the top one first. */
  internal fun closeDialogs() {
    stack.asReversed().mapNotNull { it.dismiss }.forEach { it() }
  }
}

internal val LocalFocusScopes = staticCompositionLocalOf<FocusScopes?> { null }

/** Whether a dialog holds focus, or a scope inside one does. Reading this observes it. */
internal val FocusScopes.isDialogOpen: Boolean
  get() = generateSequence(active) { it.parent }.any { it.isModal }

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

  /** The list the remembered item is in, if it's in one. */
  private var savedList: FocusMemory? = null

  /** Focuses the region: its first item, or the region itself when it has none. */
  val group = FocusRequester()

  /** Whether no items are composed. Reading this observes it. */
  val isEmpty: Boolean
    get() = items.isEmpty()

  /** Waits until the item with [key] is composed. */
  suspend fun awaitItem(key: String) {
    snapshotFlow { key in items }.first { it }
  }

  fun register(key: String, requester: FocusRequester) {
    items[key] = requester
  }

  fun unregister(key: String, requester: FocusRequester) {
    if (items[key] === requester) items.remove(key)
  }

  fun onItemFocused(key: String, list: FocusMemory? = null) {
    if (isRecording()) {
      saved.value = key
      savedList = list
    }
  }

  /** Makes [key] the remembered item, whether or not it's composed. */
  fun select(key: String) {
    saved.value = key
    savedList = null
  }

  /**
   * Focuses the item that had focus last. If it's gone, as when other rows took its place, focuses
   * the first item of the list it was in.
   */
  fun focusRemembered(): Boolean {
    val item = saved.value?.let(items::get)
    if (item != null) return item.requestFocus(FocusDirection.Enter)
    val list = savedList ?: return false
    return !list.isEmpty && list.group.requestFocus(FocusDirection.Enter)
  }
}

internal val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** The state of a [FocusScope]. */
@Stable
class FocusScopeState
internal constructor(
  saved: MutableState<String?>,
  private val scopes: FocusScopes?,
  internal val parent: FocusScopeState? = null,
  /** Closes the dialog this scope is, or null when it isn't one. */
  internal val dismiss: (() -> Unit)? = null,
) {
  internal val isModal: Boolean
    get() = dismiss != null

  internal val memory = FocusMemory(saved) { isRecording }
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

  /**
   * Puts focus back in this scope: on the item that had it last if that item is still composed,
   * otherwise on the first item of its list, or the scope's first item, or on the scope itself when
   * it has no items.
   */
  fun restore(): Boolean {
    restorePending = false
    return memory.focusRemembered() || memory.group.requestFocus(FocusDirection.Enter)
  }

  /**
   * Waits until the item with [key], such as the target of a jump, is composed, then moves focus to
   * it and remembers it as the item to restore.
   */
  suspend fun focus(key: String) {
    memory.awaitItem(key)
    memory.select(key)
    restorePending = false
    memory.focusRemembered()
  }
}

/**
 * A region that owns focus while it's active, such as a screen or a dialog, and gets it back when
 * it becomes active again.
 *
 * Every [focusItem] inside reports to the nearest scope. When this scope becomes the newest one on
 * screen (it appeared, or the screen or dialog above it went away), or while it's the newest and
 * nothing inside has focus (the focused item went away), focus returns to the item that had it
 * last, or [initialFocus]. When that item is gone, focus goes to the first item of the list it was
 * in, so a row that other rows replace leaves focus among them, or else to the scope's first item.
 * The remembered item survives this scope leaving composition the way [rememberSaveable] state
 * does.
 *
 * A scope with no items, such as an empty or loading screen, holds focus itself, so actions like
 * [Action.Back][dev.sargunv.handheld.ui.input.Action.Back] still reach it and its ancestors.
 */
@Composable
fun FocusScope(
  modifier: Modifier = Modifier,
  initialFocus: String? = null,
  content: @Composable BoxScope.() -> Unit,
) = FocusScope(modifier, initialFocus, dismiss = null, content)

/**
 * A [FocusScope] that, with a [dismiss], is a dialog: it stays above scopes appearing outside it
 * while it's open, and [FocusScopes.closeDialogs] calls [dismiss].
 */
@Composable
internal fun FocusScope(
  modifier: Modifier,
  initialFocus: String?,
  dismiss: (() -> Unit)?,
  content: @Composable BoxScope.() -> Unit,
) {
  val saved = rememberSaveable { mutableStateOf(initialFocus) }
  val scopes = LocalFocusScopes.current
  val parent = LocalFocusScope.current
  val state = remember(saved, scopes) { FocusScopeState(saved, scopes, parent, dismiss) }

  if (scopes != null) {
    DisposableEffect(scopes, state) {
      scopes.add(state)
      onDispose { scopes.remove(state) }
    }
    val isActive by remember(state) { derivedStateOf { state.isActive } }
    // Restore on becoming active even if something here already has focus, because Android may
    // have handed it to the first item.
    LaunchedEffect(isActive, state.hasFocus) {
      if (isActive && (state.restorePending || !state.hasFocus)) {
        // Lazy layouts compose their items during layout, so wait for a frame to find them.
        withFrameNanos {}
        state.restore()
      }
    }
  }

  CompositionLocalProvider(LocalFocusScope provides state, LocalFocusMemory provides null) {
    Box(
      modifier
        .onFocusChanged { state.hasFocus = it.hasFocus }
        .focusRequester(state.memory.group)
        // A group while it has items; otherwise a focus target itself. When items appear, the
        // scope gives up focus and the restore effect above moves it to them.
        .focusProperties { canFocus = state.memory.isEmpty }
        .focusTarget(),
      content = content,
    )
  }
}
