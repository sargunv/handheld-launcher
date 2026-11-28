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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
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
import dev.sargunv.handheld.ui.places.describes
import kotlinx.coroutines.launch

/** The third-party components, or null until they're read. */
@Composable
private fun rememberLibraries(info: AppInfo): List<Library>? {
  val libraries by produceState<List<Library>?>(null, info) { value = info.libraries() }
  return libraries
}

/**
 * About: the app's name and version, its licence and where its source is, and the third-party
 * components it's built from. Confirming a component shows its licence texts with [onLicence].
 */
@Composable
internal fun AboutScreen(state: AppState, onLicence: (Library) -> Unit) {
  val libraries = rememberLibraries(state.platform.appInfo)
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
        Text("Version ${state.platform.appInfo.version}", color = Placeholder.mutedText)
        Text("Licence · GPL-3.0-or-later", color = Placeholder.mutedText)
        Text("No warranty", color = Placeholder.mutedText)
        Text(
          "Source · $SourceUrl",
          color = Placeholder.text,
          modifier = Modifier.testTag("about:source"),
        )
      }
      Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Third-party components",
          color = Placeholder.mutedText,
          modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        when {
          libraries == null -> Unit
          libraries.isEmpty() -> Text("No components listed", color = Placeholder.mutedText)
          else ->
            FocusLazyColumn(
              Modifier.weight(1f),
              contentPadding = PaddingValues(vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(libraries, key = { it.uniqueId }) { library ->
                ComponentRow(
                  library,
                  Modifier.describes(state, Focused.OnComponent(library)),
                  onConfirm = { onLicence(library) },
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
private fun ComponentRow(library: Library, modifier: Modifier, onConfirm: () -> Unit) {
  Column(
    modifier
      .fillMaxWidth()
      .focusItem("component:${library.uniqueId}", onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    Text(library.name, color = Placeholder.text)
    Text(library.summary, color = Placeholder.mutedText, fontSize = 14.sp)
  }
}

/** A component's version and licences, in a line. */
private val Library.summary: String
  get() =
    listOfNotNull(
        artifactVersion?.ifBlank { null },
        licenses.joinToString(", ") { it.id }.ifEmpty { null },
      )
      .joinToString(" · ")

/** A licence's SPDX identifier, or its name when it has none. */
private val License.id: String
  get() = spdxId?.ifBlank { null } ?: name

/**
 * A component's licence texts, from About. Up and down scroll a little; the shoulder buttons a page
 * at a time.
 */
@Composable
internal fun LicenceScreen(state: AppState, componentId: String, name: String) {
  val library = rememberLibraries(state.platform.appInfo)?.find { it.uniqueId == componentId }
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val step = with(LocalDensity.current) { 120.dp.toPx() }
  // Nothing here takes focus, so the scope holds it and hears the scrolling actions.
  FocusScope(
    Modifier.fillMaxSize().testTag("licence").onAction { action ->
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
        name,
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
        for ((index, licence) in library?.licenses.orEmpty().withIndex()) {
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
          items(licence.licenseContent.orEmpty().trim().split(ParagraphBreak)) { paragraph ->
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
 * licences and where it's from. That's the component whose licence shows, [licenceOf], or else the
 * one focused on About.
 */
@Composable
internal fun AboutSecondary(state: AppState, licenceOf: String? = null) {
  val libraries = rememberLibraries(state.platform.appInfo)
  val library =
    when (licenceOf) {
      null -> (state.focused as? Focused.OnComponent)?.library
      else -> libraries?.find { it.uniqueId == licenceOf }
    } ?: return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      library.name,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    library.artifactVersion
      ?.ifBlank { null }
      ?.let {
        Text("Version $it", color = Placeholder.mutedText)
      }
    library.description?.ifBlank { null }?.let { Text(it, color = Placeholder.mutedText) }
    for (licence in library.licenses) {
      Text(licence.name, color = Placeholder.text, modifier = Modifier.testTag("about:licence"))
    }
    library.website?.ifBlank { null }?.let { Text(it, color = Placeholder.mutedText) }
  }
}
