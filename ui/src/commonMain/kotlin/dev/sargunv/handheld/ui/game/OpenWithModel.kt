package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
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
 * The rule chain for [platform]'s games that no game or collection choice covers, which the
 * platform's own choice and the defaults decide.
 */
internal fun platformChain(
  platform: PlatformSlug,
  launch: LaunchSettings,
  installed: Set<String>,
): List<RuleStep> =
  RuleChain.explain(
    // The subject is no game and in no collection, so only the platform's rules apply.
    LaunchSubject(game = null, platform = platform),
    launch.toRules().copy(gameOverrides = emptyMap(), collectionRules = emptyList()),
    installed,
  )

/**
 * The rule chain for [collection]'s games on [platform] that no game's own choice covers, which the
 * collection's choice, the platform's and the defaults decide.
 */
internal fun collectionChain(
  collection: CollectionId,
  platform: PlatformSlug,
  launch: LaunchSettings,
  installed: Set<String>,
): List<RuleStep> =
  RuleChain.explain(
    // The subject is no game, so no game's rule applies, and it's in only this collection.
    LaunchSubject(game = null, platform = platform, collections = setOf(collection)),
    launch.toRules().copy(gameOverrides = emptyMap()),
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
  val winner =
    platformChain(platform, launch, apps.mapTo(mutableSetOf()) { it.packageName }).firstOrNull {
      it.status == RuleStatus.Wins
    }
  val choice = winner?.choice ?: return null
  return appName(choice.packageName, choice.core, apps, platform)
}

/** An app the Open with screen offers. [known] marks a known emulator for the game's platform. */
internal data class AppOption(val choice: SavedChoice, val label: String, val known: Boolean)

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
            withCore(app.label, core.library),
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
    AppOption(SavedChoice(LaunchChoice.BUILT_IN), withCore(BUILT_IN_NAME, it.library), true)
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
    return LibretroCores.resolve(core, platform)?.let { withCore(BUILT_IN_NAME, it.library) }
      ?: BUILT_IN_NAME
  }
  val name =
    apps.firstOrNull { it.packageName == packageName }?.label
      ?: EmulatorRegistry.forPackage(packageName)?.displayName
      ?: packageName
  return if (core != null) withCore(name, core) else name
}

private fun withCore(name: String, core: String) =
  "$name · ${LibretroCores.byLibrary(core)?.retroArchFolder ?: core}"

/** A line of the rule chain on the lower screen. [wins] marks the rule that opens the game. */
internal data class ChainLine(val text: String, val wins: Boolean = false)

/**
 * The rule chain's lines, most specific first: the game's rule, its collections' rules, and its
 * platform's, which falls back to the built-in session and the best installed emulator, in the
 * order the resolver tries them. Each says what became of it.
 *
 * @param collections the game's collections, which name the collections' rules.
 * @param name names an app by package name and core.
 * @param forGame whether the chain is a game's. A platform's or a collection's chain leaves out the
 *   game's level, which doesn't apply to it, and a platform's the collections' too.
 * @param forCollection whether the chain is the games' of the one collection in [collections],
 *   whose level shows even with no choice.
 */
internal fun chainLines(
  steps: List<RuleStep>,
  collections: List<Collection>,
  platformName: String,
  forGame: Boolean = true,
  forCollection: Boolean = false,
  name: (String, String?) -> String,
): List<ChainLine> {
  fun line(
    level: String?,
    step: RuleStep,
    default: Boolean = false,
    app: String = step.choice?.let { name(it.packageName, it.core) } ?: "none",
  ): ChainLine {
    val note =
      when (step.status) {
        RuleStatus.Unset -> ""
        RuleStatus.Wins -> " · opens here"
        RuleStatus.NotInstalled -> " · not installed, skipped"
        RuleStatus.NoCore -> " · no core for $platformName, skipped"
        RuleStatus.Outranked -> " · overridden"
      }
    val defaultNote = if (default) " (default)" else ""
    val prefix = level?.let { "$it: " } ?: ""
    return ChainLine("$prefix$app$defaultNote$note", step.status == RuleStatus.Wins)
  }

  val platformLevel = "Platform $platformName"
  val platformRule = steps.first { it.rule == LaunchRule.PlatformDefault }
  // Unless the platform's own rule is unset or skipped, it decides or is outranked, so its
  // defaults don't matter.
  val defaultsMatter =
    platformRule.status in setOf(RuleStatus.Unset, RuleStatus.NotInstalled, RuleStatus.NoCore)
  return buildList {
    for (step in steps) {
      when (val rule = step.rule) {
        LaunchRule.GameOverride -> if (forGame) add(line("Game", step))
        is LaunchRule.CollectionOverride -> {
          val collection = collections.firstOrNull { it.launchId == rule.collection }
          add(line("Collection '${collection?.name ?: rule.collection}'", step))
        }
        LaunchRule.PlatformDefault -> {
          if (steps.none { it.rule is LaunchRule.CollectionOverride }) {
            when {
              forGame -> add(ChainLine("Collection: none"))
              forCollection ->
                add(ChainLine("Collection '${collections.firstOrNull()?.name}': none"))
            }
          }
          if (step.status != RuleStatus.Unset) add(line(platformLevel, step))
        }
        LaunchRule.BuiltInDefault ->
          when {
            !defaultsMatter -> Unit
            step.status == RuleStatus.NoCore ->
              add(line(null, step, default = true, app = BUILT_IN_NAME))
            platformRule.status == RuleStatus.Unset ->
              add(line(platformLevel, step, default = true, app = BUILT_IN_NAME))
            else -> add(line("Default for $platformName", step, app = BUILT_IN_NAME))
          }
        LaunchRule.FirstInstalled ->
          when {
            !defaultsMatter -> Unit
            platformRule.status == RuleStatus.Unset && step.choice == null ->
              add(ChainLine("$platformLevel: no emulator installed"))
            platformRule.status == RuleStatus.Unset ->
              add(line(platformLevel, step, default = true))
            step.choice != null -> add(line("Default for $platformName", step))
          }
      }
    }
  }
}
