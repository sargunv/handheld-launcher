package dev.sargunv.handheld.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.Screen
import kotlinx.coroutines.launch

/** The third-party components, or null until they're read. */
@Composable
private fun rememberComponents(info: AppInfo): List<Component>? {
  val components by produceState<List<Component>?>(null, info) { value = info.components() }
  return components
}

/**
 * About: the app's name and version, its licence and where its source is, and the third-party
 * components it's built from. Confirming a component shows its licence texts.
 */
@Composable
internal fun AboutScreen(state: AppState) {
  val components = rememberComponents(state.appInfo)
  // The lower screen describes a component only while this screen shows.
  DisposableEffect(state) {
    onDispose { if (state.focused is Focused.OnComponent) state.focused = null }
  }
  Column(Modifier.fillMaxSize()) {
    Text(
      "About",
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier =
        Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp).semantics {
          heading()
        },
    )
    Row(
      Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
      Column(
        Modifier.weight(2f).padding(top = 8.dp).testTag("about:app"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          AppName,
          color = Placeholder.text,
          fontSize = 24.sp,
          modifier = Modifier.semantics { heading() },
        )
        Text("Version ${state.appInfo.version}", color = Placeholder.mutedText)
        Text(
          "$AppName is free software under the GNU General Public License, version 3 or (at your " +
            "option) any later version. You can share and change it under the licence's terms. " +
            "It comes with no warranty.",
          color = Placeholder.mutedText,
        )
        Text("Its source code and the licence's full text are at", color = Placeholder.mutedText)
        Text(SourceUrl, color = Placeholder.text, modifier = Modifier.testTag("about:source"))
      }
      Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Third-party components",
          color = Placeholder.mutedText,
          modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        when {
          components == null -> Unit
          components.isEmpty() ->
            Text("This build doesn't list its components.", color = Placeholder.mutedText)
          else ->
            FocusLazyColumn(
              Modifier.weight(1f),
              contentPadding = PaddingValues(vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(components, key = { it.id }) { component ->
                ComponentRow(
                  component,
                  onFocus = { state.focused = Focused.OnComponent(component) },
                  onConfirm = {
                    state.navigator.push(Screen.Licence(component.id, component.name))
                  },
                )
              }
            }
        }
      }
    }
    HintBar(listOf(Hint(Action.Confirm, "Licence"), Hint(Action.Back, "Back")))
  }
}

/** A component in About's list: its name, version and licences. */
@Composable
private fun ComponentRow(component: Component, onFocus: () -> Unit, onConfirm: () -> Unit) {
  Column(
    Modifier.fillMaxWidth()
      .onFocusChanged { if (it.isFocused) onFocus() }
      .focusItem("component:${component.id}", onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    Text(component.name, color = Placeholder.text)
    Text(component.summary, color = Placeholder.mutedText, fontSize = 14.sp)
  }
}

/** A component's version and licences, in a line. */
private val Component.summary: String
  get() =
    listOfNotNull(version, licences.joinToString(", ") { it.id }.ifEmpty { null })
      .joinToString(" · ")

/**
 * A component's licence texts, from About. Up and down scroll a little; the shoulder buttons a page
 * at a time.
 */
@Composable
internal fun LicenceScreen(state: AppState, screen: Screen.Licence) {
  val component = rememberComponents(state.appInfo)?.find { it.id == screen.componentId }
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val step = with(LocalDensity.current) { 120.dp.toPx() }
  // Nothing here takes focus, so the scope holds it and hears the scrolling actions. It says so to
  // accessibility services, as an item would.
  var holdsFocus by remember { mutableStateOf(false) }
  FocusScope(
    Modifier.fillMaxSize()
      .testTag("licence")
      .semantics { focused = holdsFocus }
      .onFocusChanged { holdsFocus = it.isFocused }
      .onAction { action ->
        val page = list.layoutInfo.viewportSize.height - step
        val distance =
          when (action) {
            Action.Up -> -step
            Action.Down -> step
            Action.PreviousShelf -> -page
            Action.NextShelf -> page
            else -> return@onAction false
          }
        scope.launch { list.scrollBy(distance) }
        true
      },
  ) {
    Column(Modifier.fillMaxSize()) {
      Text(
        screen.name,
        color = Placeholder.text,
        fontSize = 28.sp,
        modifier =
          Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp).semantics {
            heading()
          },
      )
      LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        state = list,
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        for ((index, licence) in component?.licences.orEmpty().withIndex()) {
          item(key = "licence:$index") {
            Text(
              licence.name,
              color = Placeholder.text,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
              modifier =
                Modifier.padding(top = if (index > 0) 24.dp else 0.dp)
                  .testTag("licence:${licence.id}")
                  .semantics { heading() },
            )
          }
          // A paragraph at a time, so a long licence composes only what shows.
          items(licence.text.trim().split(ParagraphBreak)) { paragraph ->
            Text(
              paragraph,
              color = Placeholder.text,
              fontSize = 16.sp,
              modifier = Modifier.widthIn(max = 1200.dp),
            )
          }
        }
      }
      HintBar(
        listOf(
          Hint(Action.Down, "Scroll"),
          Hint(Action.NextShelf, "Next page"),
          Hint(Action.Back, "Back"),
        ),
      )
    }
  }
}

private val ParagraphBreak = Regex("""\n\s*\n""")

/**
 * The lower screen for About and a licence: what the component in question is, its version, its
 * licences and where it's from.
 */
@Composable
internal fun AboutSecondary(state: AppState, screen: Screen) {
  val components = rememberComponents(state.appInfo)
  val component =
    when (screen) {
      is Screen.Licence -> components?.find { it.id == screen.componentId }
      else -> (state.focused as? Focused.OnComponent)?.component
    } ?: return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      component.name,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    component.version?.let { Text("Version $it", color = Placeholder.mutedText) }
    component.description?.let { Text(it, color = Placeholder.mutedText) }
    for (licence in component.licences) {
      Text(licence.name, color = Placeholder.text, modifier = Modifier.testTag("about:licence"))
    }
    component.website?.let { Text(it, color = Placeholder.mutedText) }
  }
}
