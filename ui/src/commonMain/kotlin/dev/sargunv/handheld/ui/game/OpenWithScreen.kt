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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.LaunchChoice
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

/** What the Open with screens show about a game: its collections, and the apps it can open in. */
private class OpenWithGame(
  val rom: Rom,
  val platformName: String,
  val collections: List<Collection>,
  val apps: List<InstalledApp>,
) {
  val platform = PlatformSlug(rom.platformSlug)

  /**
   * How far a choice can reach, with each level's name: the game, each collection, the platform.
   */
  val levels: List<Pair<RuleLevel, String>> =
    listOf(RuleLevel.Game(rom.id) to "This game") +
      collections.map {
        RuleLevel.InCollection(it.launchId, platform) to "Collection '${it.name}'"
      } +
      (RuleLevel.Platform(platform) to "All $platformName games")

  fun chain(launch: LaunchSettings): List<RuleStep> =
    RuleChain.explain(
      LaunchSubject(rom.id, platform, collections.mapTo(mutableSetOf()) { it.launchId }),
      launch.toRules(),
      apps.mapTo(mutableSetOf()) { it.packageName },
    )

  fun name(packageName: String, core: String?) = appName(packageName, core, apps, platform)
}

@Composable
private fun rememberOpenWithGame(state: AppState, romId: Long): OpenWithGame? {
  val game by
    produceState<OpenWithGame?>(null, romId) {
      val rom = state.library.rom(romId) ?: return@produceState
      value =
        OpenWithGame(
          rom,
          state.library.platform(rom.platformId)?.name ?: rom.platformSlug,
          state.library.collectionsOf(rom.id),
          state.apps.apps(),
        )
    }
  return game
}

/**
 * Picks the app a game opens in: any installed app, known emulators for its platform first, for the
 * game alone, one of its collections, or its whole platform. Choosing an app, or clearing the
 * level's choice, goes back to the game.
 */
@Composable
internal fun OpenWithScreen(state: AppState, screen: Screen.OpenWith) {
  val game = rememberOpenWithGame(state, screen.romId)
  val launch = state.settings.launch
  // The level the player picked, by index; until they pick, the most specific one with a choice.
  var picked by rememberSaveable { mutableIntStateOf(-1) }
  Column(Modifier.fillMaxSize()) {
    Text(
      "Open ${screen.title} with",
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp),
    )
    if (game == null) {
      Spacer(Modifier.weight(1f))
    } else {
      val levels = game.levels
      val levelIndex =
        picked.takeIf { it in levels.indices }
          ?: levels.indexOfFirst { launch.choiceAt(it.first) != null }.takeIf { it >= 0 }
          ?: 0
      val level = levels[levelIndex].first
      val current = launch.choiceAt(level)
      // The app the platform's defaults pick, which opens the game when no choice applies.
      val default =
        game
          .chain(launch)
          .firstOrNull {
            (it.rule == LaunchRule.BuiltInDefault || it.rule == LaunchRule.FirstInstalled) &&
              it.status != RuleStatus.NoCore &&
              it.packageName != null
          }
          ?.packageName
      fun choose(choice: SavedChoice?) {
        state.settings = state.settings.copy(launch = launch.withChoice(level, choice))
        state.navigator.back()
      }
      Row(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
      ) {
        val options = appOptions(game.platform, game.apps)
        if (options.isEmpty()) {
          Text(
            "No apps on this device can open games.",
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
                  "${game.platformName} default"
                    .takeIf {
                      option.known && option.choice.packageName == default
                    },
                  "current".takeIf { option.choice == current },
                )
              OptionRow(
                key = "app:${option.choice.packageName}:${option.choice.core}",
                label = option.label,
                note = notes.joinToString(" · ").ifEmpty { null },
                background = Placeholder.tile,
                onConfirm = { choose(option.choice) },
              )
            }
          }
        }
        // A game in many collections has more levels than fit, so the column scrolls.
        Column(
          Modifier.width(360.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text("Use for", color = Placeholder.mutedText, modifier = Modifier.padding(top = 8.dp))
          levels.forEachIndexed { index, (rowLevel, name) ->
            val choice = launch.choiceAt(rowLevel)
            OptionRow(
              key = "level:$index",
              label = if (index == levelIndex) "● $name" else name,
              note = choice?.let { game.name(it.packageName, it.core) } ?: "Not set",
              background = if (index == levelIndex) Placeholder.tile else Placeholder.background,
              onConfirm = { picked = index },
            )
          }
          if (current != null) {
            Spacer(Modifier.width(8.dp))
            MenuButton("clear", "Clear this choice") { choose(null) }
          }
        }
      }
    }
    HintBar(listOf(Hint(Action.Confirm, "Choose"), Hint(Action.Back, "Back")))
  }
}

@Composable
private fun OptionRow(
  key: String,
  label: String,
  note: String?,
  background: Color,
  onConfirm: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .testTag(key)
      .focusItem(key, onConfirm = onConfirm)
      .background(background)
      .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    Text(label, color = Placeholder.text)
    note?.let { Text(it, color = Placeholder.mutedText, fontSize = 14.sp) }
  }
}

/** The lower screen for Open with: the rule chain that decides where the game opens, and why. */
@Composable
internal fun OpenWithSecondary(state: AppState, screen: Screen.OpenWith) {
  val game = rememberOpenWithGame(state, screen.romId) ?: return
  val steps = game.chain(state.settings.launch)
  val winner = steps.firstOrNull { it.status == RuleStatus.Wins }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val winnerApp = winner?.packageName
    val opensIn =
      when (winnerApp) {
        null -> null
        LaunchChoice.BUILT_IN -> "the built-in session"
        else -> game.name(winnerApp, winner.core)
      }
    Text(
      opensIn?.let { "Why it opens in $it" } ?: "No app on this device can open it",
      color = Placeholder.text,
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
    )
    val lines = chainLines(steps, game.collections, game.platformName, game::name)
    lines.forEachIndexed { index, line ->
      Text(
        (if (index > 0) "→ " else "") + line.text,
        color = if (line.wins) Placeholder.text else Placeholder.mutedText,
        fontWeight = if (line.wins) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.testTag("chain"),
      )
    }
    Text("The most specific choice wins.", color = Placeholder.mutedText)
  }
}
