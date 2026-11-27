package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRule
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.RuleChain
import dev.sargunv.handheld.domain.launch.RuleStatus
import dev.sargunv.handheld.domain.launch.RuleStep
import dev.sargunv.handheld.domain.platform.PlatformSlug

/** An app installed on this device, by package name and the name it shows. */
data class InstalledApp(val packageName: String, val label: String)

/** The apps on this device that can open games. Each platform supplies its own. */
fun interface AppCatalog {
  /** Every app with a launcher icon, other than this one. */
  suspend fun apps(): List<InstalledApp>

  companion object {
    /** A catalog for platforms that can't open games in other apps. */
    val None = AppCatalog { emptyList() }
  }
}

/**
 * What a choice on the Open with screen applies to, which comes from where the screen was opened: a
 * game's menu chooses for the game, a platform's menu or Settings → Emulators for the platform's
 * games, and a collection's menu for the collection's games on one platform.
 */
@Immutable
sealed interface OpenWithScope {
  data class Game(val game: GameKey) : OpenWithScope

  data class Platform(val platform: PlatformSlug) : OpenWithScope

  /** The games of [collection] on [platform], since an app opens one platform's games. */
  data class Collection(val collection: CollectionId, val platform: PlatformSlug) : OpenWithScope
}

/**
 * The rule chain for what [scope] chooses for, on [platform]: its game, its platform's games, or
 * its collection's games. [collections] are the game's, or the scope's one collection.
 */
internal fun chainFor(
  scope: OpenWithScope,
  platform: PlatformSlug,
  collections: List<Collection>,
  launch: LaunchSettings,
  installed: Set<String>,
): List<RuleStep> =
  RuleChain.explain(
    LaunchSubject(
      (scope as? OpenWithScope.Game)?.game,
      platform,
      collections.mapTo(mutableSetOf()) { it.launchId },
    ),
    launch.toRules(),
    installed,
  )

/**
 * The app [platform]'s games open in when nothing more specific applies, such as "Built-in · mGBA"
 * or "PPSSPP", or null when no app on the device can open them.
 */
internal fun platformOpensIn(
  platform: PlatformSlug,
  launch: LaunchSettings,
  apps: List<InstalledApp>,
): String? {
  val installed = apps.mapTo(mutableSetOf()) { it.packageName }
  val winner =
    chainFor(OpenWithScope.Platform(platform), platform, emptyList(), launch, installed)
      .firstOrNull { it.status == RuleStatus.Wins }
  val choice = winner?.choice ?: return null
  return appName(choice.packageName, choice.core, apps, platform)
}

/** An app the Open with screen offers. [known] marks a known emulator for the game's platform. */
internal data class AppOption(val choice: SavedChoice, val label: String, val known: Boolean) {
  /** The option's key in the list, and its row's focus key. */
  val key: String
    get() = "app:${choice.packageName}:${choice.core}"
}

/**
 * The apps a game on [platform] can open in: the built-in session when a libretro core runs the
 * platform, then the installed known emulators for the platform, best first, then every other app
 * by name. An app that loads a libretro core comes with the platform's known core.
 */
internal fun appOptions(platform: PlatformSlug, apps: List<InstalledApp>): List<AppOption> {
  val ranking = EmulatorRegistry.forPlatform(platform)
  val (known, others) =
    apps.partition { app -> EmulatorRegistry.forPackage(app.packageName) in ranking }
  val core = LibretroCores.recommendedFor(platform)
  val knownOptions =
    known
      .sortedWith(
        compareBy(
          { ranking.indexOf(EmulatorRegistry.forPackage(it.packageName)) },
          { it.packageName },
        ),
      )
      .map { app ->
        val emulator = EmulatorRegistry.forPackage(app.packageName)
        if (emulator?.recipe?.loadsCore == true && core != null) {
          AppOption(
            SavedChoice(app.packageName, core.library),
            withCore(app.label, core),
            true,
          )
        } else {
          AppOption(SavedChoice(app.packageName), app.label, true)
        }
      }
  val otherOptions =
    others.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).map {
      AppOption(SavedChoice(it.packageName), it.label, false)
    }
  // The built-in session picks the platform's core when it plays, so the choice doesn't name one.
  val builtIn = core?.let {
    AppOption(SavedChoice(LaunchChoice.BUILT_IN), withCore(BUILT_IN_NAME, it), true)
  }
  return listOfNotNull(builtIn) + knownOptions + otherOptions
}

/** The built-in session's name wherever the app names it. */
private const val BUILT_IN_NAME = "Built-in"

/**
 * The name of [packageName]'s app, with [core] when it names one: its label when it's installed,
 * else the known emulator's name, else the package name. The built-in session, which runs a game on
 * [platform] in the platform's core unless [core] names one, is "Built-in" with its core.
 */
internal fun appName(
  packageName: String,
  core: String?,
  apps: List<InstalledApp>,
  platform: PlatformSlug,
): String {
  if (packageName == LaunchChoice.BUILT_IN) {
    return LibretroCores.resolve(core, platform)?.let { withCore(BUILT_IN_NAME, it) }
      ?: BUILT_IN_NAME
  }
  val name =
    apps.firstOrNull { it.packageName == packageName }?.label
      ?: EmulatorRegistry.forPackage(packageName)?.displayName
      ?: packageName
  return core?.let { LibretroCores.resolve(it, platform) }?.let { withCore(name, it) } ?: name
}

private fun withCore(name: String, core: LibretroCore) = "$name · ${core.retroArchFolder}"

/** A line of the rule chain on the lower screen. [wins] marks the rule that opens the game. */
internal data class ChainLine(val text: String, val wins: Boolean = false)

/**
 * The rule chain's lines, one for each step, each naming its level and app and saying what became
 * of it.
 *
 * @param collections the collections the steps can name.
 * @param name names an app by package name and core.
 */
internal fun chainLines(
  steps: List<RuleStep>,
  collections: List<Collection>,
  platformName: String,
  name: (String, String?) -> String,
): List<ChainLine> = steps.map { step ->
  val level =
    when (val rule = step.rule) {
      LaunchRule.GameOverride -> "Game"
      is LaunchRule.CollectionOverride ->
        rule.collection?.let { id ->
          "Collection '${collections.firstOrNull { it.launchId == id }?.name ?: id}'"
        } ?: "Collection"
      LaunchRule.PlatformDefault,
      LaunchRule.BuiltInDefault,
      LaunchRule.FirstInstalled -> "Platform $platformName"
    }
  val app = step.choice?.let { name(it.packageName, it.core) } ?: "none"
  val default = if (step.isDefault) " (default)" else ""
  val note =
    when (step.status) {
      RuleStatus.Unset -> ""
      RuleStatus.Wins -> " · opens here"
      RuleStatus.NotInstalled -> " · not installed, skipped"
      RuleStatus.NoCore -> " · no core for $platformName, skipped"
      RuleStatus.Outranked -> " · overridden"
    }
  ChainLine("$level: $app$default$note", step.status == RuleStatus.Wins)
}
