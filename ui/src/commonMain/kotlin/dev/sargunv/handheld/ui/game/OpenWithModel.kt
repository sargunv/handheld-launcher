package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRule
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

/** The built-in session's name in the app list. */
private const val BUILT_IN_NAME = "Built-in"

/** The built-in session's name as the default in the rule chain. */
private const val BUILT_IN_SESSION = "Built-in session"

/**
 * The name of [packageName]'s app, with [core] when it names one: its label when it's installed,
 * else the known emulator's name, else the package name. The built-in session, which runs a game on
 * [platform] in the platform's core unless [core] names one, is "Built-in" with its core.
 */
internal fun appName(
  packageName: String,
  core: String?,
  apps: List<InstalledApp>,
  platform: PlatformSlug? = null,
): String {
  if (packageName == LaunchChoice.BUILT_IN) {
    val builtInCore = core ?: platform?.let { LibretroCores.recommendedFor(it)?.library }
    return builtInCore?.let { withCore(BUILT_IN_NAME, it) } ?: BUILT_IN_NAME
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
 */
internal fun chainLines(
  steps: List<RuleStep>,
  collections: List<Collection>,
  platformName: String,
  name: (String, String?) -> String,
): List<ChainLine> {
  fun line(
    level: String?,
    step: RuleStep,
    default: Boolean = false,
    app: String = step.packageName?.let { name(it, step.core) } ?: "none",
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
        LaunchRule.GameOverride -> add(line("Game", step))
        is LaunchRule.CollectionOverride -> {
          val collection = collections.firstOrNull { it.launchId == rule.collection }
          add(line("Collection '${collection?.name ?: rule.collection}'", step))
        }
        LaunchRule.PlatformDefault -> {
          if (steps.none { it.rule is LaunchRule.CollectionOverride }) {
            add(ChainLine("Collection: none"))
          }
          if (step.status != RuleStatus.Unset) add(line(platformLevel, step))
        }
        LaunchRule.BuiltInDefault ->
          when {
            !defaultsMatter -> Unit
            step.status == RuleStatus.NoCore ->
              add(line(null, step, default = true, app = BUILT_IN_SESSION))
            platformRule.status == RuleStatus.Unset ->
              add(line(platformLevel, step, default = true, app = BUILT_IN_SESSION))
            else -> add(line("Default for $platformName", step, app = BUILT_IN_SESSION))
          }
        LaunchRule.FirstInstalled ->
          when {
            !defaultsMatter -> Unit
            platformRule.status == RuleStatus.Unset && step.packageName == null ->
              add(ChainLine("$platformLevel: no emulator installed"))
            platformRule.status == RuleStatus.Unset ->
              add(line(platformLevel, step, default = true))
            step.packageName != null -> add(line("Default for $platformName", step))
          }
      }
    }
  }
}
