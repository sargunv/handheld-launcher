package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.LaunchRule
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.RuleChain
import dev.sargunv.handheld.domain.launch.RuleStatus
import dev.sargunv.handheld.domain.launch.RuleStep
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import kotlinx.coroutines.flow.first

/**
 * What the Open with screens show: the platform the choice is about, the game's collections when
 * it's for a game, and the apps the games can open in.
 */
private class OpenWithContext(
  val scope: OpenWithScope,
  val platform: PlatformSlug,
  val platformName: String,
  val collections: List<Collection>,
  val apps: List<InstalledApp>,
) {
  private val installed = apps.mapTo(mutableSetOf()) { it.packageName }

  val forGame: Boolean
    get() = scope is OpenWithScope.Game

  /** What the choice is for, such as "this game" or "Game Boy games in Favourites". */
  val subject: String
    get() =
      when (scope) {
        is OpenWithScope.Game -> "this game"
        is OpenWithScope.Platform -> "$platformName games"
        is OpenWithScope.Collection -> "$platformName games in ${collections.first().name}"
      }

  fun chain(launch: LaunchSettings): List<RuleStep> =
    when (scope) {
      is OpenWithScope.Game ->
        RuleChain.explain(
          LaunchSubject(scope.game, platform, collections.mapTo(mutableSetOf()) { it.launchId }),
          launch.toRules(),
          installed,
        )
      is OpenWithScope.Platform -> platformChain(platform, launch, installed)
      is OpenWithScope.Collection -> collectionChain(scope.collection, platform, launch, installed)
    }

  /** The app that wins under [launch], by name, or null when none can open the games. */
  fun opensIn(launch: LaunchSettings): String? {
    val winner = chain(launch).firstOrNull { it.status == RuleStatus.Wins } ?: return null
    return winner.choice?.let { name(it.packageName, it.core) }
  }

  fun name(packageName: String, core: String?) = appName(packageName, core, apps, platform)
}

@Composable
private fun rememberOpenWithContext(state: AppState, scope: OpenWithScope): OpenWithContext? {
  val context by
    produceState<OpenWithContext?>(null, scope) {
      value =
        when (scope) {
          is OpenWithScope.Game -> {
            val game = state.game(scope.game) ?: return@produceState
            OpenWithContext(
              scope,
              PlatformSlug(game.platformSlug),
              state.library.platform(game.platformId)?.name ?: game.platformSlug,
              // A local game is in no collection.
              (game as? Rom)?.let { state.library.collectionsOf(it.id) }.orEmpty(),
              state.apps.apps(),
            )
          }
          is OpenWithScope.Platform ->
            OpenWithContext(
              scope,
              scope.platform,
              platformName(state, scope.platform),
              emptyList(),
              state.apps.apps(),
            )
          is OpenWithScope.Collection -> {
            val collection =
              state.library.collections().first().find { it.launchId == scope.collection }
                ?: return@produceState
            OpenWithContext(
              scope,
              scope.platform,
              platformName(state, scope.platform),
              listOf(collection),
              state.apps.apps(),
            )
          }
        }
    }
  return context
}

private suspend fun platformName(state: AppState, platform: PlatformSlug): String =
  state.library.platforms().first().find { it.slug == platform.value }?.name ?: platform.value

/**
 * Picks the app games open in, for what the screen was opened from: one game from its menu, a
 * platform's games from its menu or Settings → Emulators, or a collection's games on one platform
 * from the collection's menu. Any installed app can be picked, known emulators for the platform
 * first. Choosing an app, or clearing the choice, goes back.
 */
@Composable
internal fun OpenWithScreen(state: AppState, screen: Screen.OpenWith) {
  val scope = screen.scope
  val context = rememberOpenWithContext(state, scope)
  val launch = state.settings.launch
  Column(Modifier.fillMaxSize()) {
    Text(
      when (scope) {
        is OpenWithScope.Game -> "Open ${screen.title} with"
        is OpenWithScope.Platform -> "Open ${screen.title} games with"
        is OpenWithScope.Collection ->
          context?.let { "Open ${it.subject} with" } ?: "Open ${screen.title} games with"
      },
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier =
        Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp).semantics {
          heading()
        },
    )
    if (context == null) {
      Spacer(Modifier.weight(1f))
    } else {
      val current = launch.choiceAt(scope)
      // The app the platform's defaults pick, which opens the games when no choice applies.
      val default =
        context
          .chain(launch)
          .firstOrNull {
            (it.rule == LaunchRule.BuiltInDefault || it.rule == LaunchRule.FirstInstalled) &&
              it.status != RuleStatus.NoCore &&
              it.choice != null
          }
          ?.choice
          ?.packageName
      fun choose(choice: SavedChoice?) {
        state.settings = state.settings.copy(launch = launch.withChoice(scope, choice))
        state.navigator.back()
      }
      Row(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
      ) {
        val options = appOptions(context.platform, context.apps)
        if (options.isEmpty()) {
          Text(
            "No apps on this device",
            color = Placeholder.mutedText,
            modifier = Modifier.weight(1f),
          )
        } else {
          FocusLazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(options, key = { "app:${it.choice.packageName}:${it.choice.core}" }) { option ->
              val notes =
                listOfNotNull(
                  "${context.platformName} default"
                    .takeIf { option.known && option.choice.packageName == default },
                  "current".takeIf { option.choice == current },
                )
              OptionRow(
                key = "app:${option.choice.packageName}:${option.choice.core}",
                label = option.label,
                note = notes.joinToString(" · ").ifEmpty { null },
                onConfirm = { choose(option.choice) },
              )
            }
          }
        }
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "Chosen for ${context.subject}",
            color = Placeholder.mutedText,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
          )
          Text(
            current?.let { context.name(it.packageName, it.core) } ?: "Nothing chosen",
            color = Placeholder.text,
            modifier = Modifier.testTag("openWith:current"),
          )
          if ((scope as? OpenWithScope.Game)?.game is GameKey.Local) {
            Text(
              "Kept for this file · Moves to its game once on your server",
              color = Placeholder.mutedText,
            )
          }
          if (current != null) {
            Text(
              "If cleared: ${context.opensIn(launch.withChoice(scope, null)) ?: "no app"}",
              color = Placeholder.mutedText,
            )
            MenuButton("clear", "Clear this choice") { choose(null) }
          }
        }
      }
    }
    HintBar(listOf(Hint(Action.Confirm, "Choose"), Hint(Action.Back, "Back")))
  }
}

/** An app to choose, with [note] saying whether it's the default or the current choice. */
@Composable
private fun OptionRow(
  key: String,
  label: String,
  note: String?,
  onConfirm: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .focusItem(key, onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    Text(label, color = Placeholder.text)
    note?.let { Text(it, color = Placeholder.mutedText, fontSize = 14.sp) }
  }
}

/**
 * The lower screen for Open with: the rule chain that decides where the game, or the platform's
 * games, open, and why.
 */
@Composable
internal fun OpenWithSecondary(state: AppState, screen: Screen.OpenWith) {
  val context = rememberOpenWithContext(state, screen.scope) ?: return
  val launch = state.settings.launch
  val steps = context.chain(launch)
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val opensIn = context.opensIn(launch)
    val subject = if (context.forGame) "it" else context.subject
    Text(
      when {
        opensIn == null -> "No app on this device can open $subject"
        context.forGame -> "Why it opens in $opensIn"
        else -> "Why $subject open in $opensIn"
      },
      color = Placeholder.text,
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { heading() },
    )
    val lines =
      chainLines(
        steps,
        context.collections,
        context.platformName,
        context.forGame,
        forCollection = context.scope is OpenWithScope.Collection,
        name = context::name,
      )
    lines.forEachIndexed { index, line ->
      Text(
        (if (index > 0) "→ " else "") + line.text,
        color = if (line.wins) Placeholder.text else Placeholder.mutedText,
        fontWeight = if (line.wins) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.testTag("chain"),
      )
    }
    Text(
      "Game, then collection, then platform",
      color = Placeholder.mutedText,
    )
  }
}
