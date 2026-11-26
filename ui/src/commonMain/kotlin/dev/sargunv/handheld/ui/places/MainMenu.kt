package dev.sargunv.handheld.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/** The places the main menu offers: all of them once signed in, and only Settings before. */
internal fun mainMenuPlaces(signedIn: Boolean): List<Place> =
  if (signedIn) Place.entries else listOf(Place.Settings)

/**
 * The main menu, on Start from any launcher screen: a side menu over the screen that goes to the
 * top-level places. Back or Start closes it. Before sign-in it offers only Settings, which shows
 * over the sign-in.
 */
@Composable
internal fun MainMenu(state: AppState, onDismissRequest: () -> Unit) {
  val signedIn = state.signedIn == true
  val places = mainMenuPlaces(signedIn)
  // The place showing, when nothing covers it.
  val current =
    when {
      !signedIn -> if (state.settingsOverSignIn) Place.Settings else null
      state.navigator.current == Screen.Places -> state.place
      else -> null
    }
  val queue by state.downloads.queue.collectAsState()
  Dialog(
    onDismissRequest,
    Modifier.fillMaxHeight().width(320.dp),
    initialFocus = "main:${(current ?: places.first()).name}",
    alignment = Alignment.CenterStart,
  ) {
    Column(
      Modifier.onAction { action ->
        if (action == Action.MainMenu) {
          onDismissRequest()
          true
        } else {
          false
        }
      },
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Menu",
        color = Placeholder.mutedText,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
      )
      for (place in places) {
        val queued = if (place == Place.Downloads) queue.size else 0
        MainMenuItem(place, queued, selected = place == current) {
          onDismissRequest()
          if (signedIn) state.goTo(place) else state.settingsOverSignIn = true
        }
      }
    }
  }
}

@Composable
private fun MainMenuItem(place: Place, queued: Int, selected: Boolean, onConfirm: () -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .semantics { this.selected = selected }
      .focusItem(
        "main:${place.name}",
        onConfirm = onConfirm,
        description = if (queued > 0) "${place.title}, $queued in the queue" else null,
      )
      .background(if (selected) Placeholder.tile else Placeholder.dialog)
      .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      place.title,
      color = Placeholder.text,
      fontSize = 18.sp,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
    Spacer(Modifier.weight(1f))
    if (queued > 0) Text("$queued", color = Placeholder.mutedText)
  }
}
