package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.KnownEmulator
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformSlug

/** Which rule picked the app for a game. */
sealed interface LaunchRule {
  /** The game's own override. */
  data object GameOverride : LaunchRule

  /** The rule of [collection], one of the game's collections. */
  data class CollectionOverride(val collection: CollectionId) : LaunchRule

  /** The default for the game's platform. */
  data object PlatformDefault : LaunchRule

  /** Nothing named an app, and a libretro core runs the platform, so the built-in session does. */
  data object BuiltInDefault : LaunchRule

  /** Nothing named an app, so the best installed known emulator for the platform runs it. */
  data object FirstInstalled : LaunchRule
}

/**
 * The app a game launches in, and how. [emulator] is the known emulator [packageName] is, or null
 * for an app the registry doesn't know.
 */
data class LaunchTarget(
  val packageName: String,
  val recipe: LaunchRecipe,
  val emulator: KnownEmulator? = null,
  val core: String? = null,
) {
  /**
   * The command that launches [input] here, with this target's [core] unless [input] names one. A
   * known core loads by its Android library name, which can differ from [core]'s.
   */
  fun commandFor(input: LaunchInput): LaunchBuild {
    val androidCore = core?.let { LibretroCores.byLibrary(it)?.androidLibrary ?: it }
    return LaunchCommandBuilder.build(
      recipe,
      packageName,
      input.copy(core = input.core ?: androidCore),
    )
  }

  /**
   * The libretro core, by library name, that a game on [platform] runs in here: this target's
   * [core], or the platform's recommended one, which a libretro launch falls back to.
   */
  fun coreFor(platform: PlatformSlug): String? =
    core ?: LibretroCores.recommendedFor(platform)?.library
}

/** A rule that named an app that isn't installed, so resolution moved past it. */
data class SkippedChoice(val rule: LaunchRule, val packageName: String)

/** The outcome of picking the app for a game, with the reasons. */
sealed interface LaunchResolution {
  /** Rules that named an app that isn't installed, most specific first. */
  val skipped: List<SkippedChoice>

  data class Resolved(
    val target: LaunchTarget,
    val rule: LaunchRule,
    override val skipped: List<SkippedChoice> = emptyList(),
  ) : LaunchResolution

  /**
   * The game runs in the built-in session. [core] is the libretro core the rule named, by library
   * name, or null for the platform's chosen core.
   */
  data class BuiltIn(
    val rule: LaunchRule,
    val core: String? = null,
    override val skipped: List<SkippedChoice> = emptyList(),
  ) : LaunchResolution

  /**
   * No rule named an installed app, and no known emulator for [platform] is installed.
   * [suggestions] are the known emulators that would run it, best first.
   */
  data class NoEmulatorInstalled(
    val platform: PlatformSlug,
    val suggestions: List<KnownEmulator>,
    override val skipped: List<SkippedChoice> = emptyList(),
  ) : LaunchResolution
}

/**
 * Picks the app a game launches in: the game's override, then the first of its collections' rules
 * that applies, then its platform's default, then the built-in session when a libretro core runs
 * the platform, then the best installed known emulator. For the platforms an installed emulator
 * plays better ([LibretroCores.prefersStandalone]), the emulator comes before the built-in session.
 * A rule may name any app or the built-in session. One whose app isn't installed is skipped and
 * reported, as is the built-in session for a platform no libretro core runs.
 */
object LaunchResolver {
  fun resolve(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): LaunchResolution {
    val candidates = buildList {
      subject.game?.let(rules.gameOverrides::get)?.let { add(LaunchRule.GameOverride to it) }
      rules.collectionRulesFor(subject).forEach {
        add(LaunchRule.CollectionOverride(it.collection) to it.choice)
      }
      rules.platformDefaults[subject.platform]?.let { add(LaunchRule.PlatformDefault to it) }
    }

    val runsBuiltIn = LibretroCores.recommendedFor(subject.platform) != null
    val skipped = mutableListOf<SkippedChoice>()
    for ((rule, choice) in candidates) {
      if (choice.isBuiltIn && runsBuiltIn) {
        return LaunchResolution.BuiltIn(rule, choice.core, skipped.toList())
      }
      if (!choice.isBuiltIn && choice.packageName in installedPackages) {
        return LaunchResolution.Resolved(targetFor(choice), rule, skipped.toList())
      }
      skipped += SkippedChoice(rule, choice.packageName)
    }
    val standaloneFirst = LibretroCores.prefersStandalone(subject.platform)
    if (runsBuiltIn && !standaloneFirst)
      return LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault, null, skipped.toList())

    firstInstalled(subject.platform, installedPackages)?.let {
      return LaunchResolution.Resolved(it, LaunchRule.FirstInstalled, skipped.toList())
    }
    if (runsBuiltIn)
      return LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault, null, skipped.toList())
    val known = EmulatorRegistry.forPlatform(subject.platform)
    return LaunchResolution.NoEmulatorInstalled(subject.platform, known, skipped.toList())
  }

  /** The best installed known emulator for [platform], or null when none is installed. */
  internal fun firstInstalled(
    platform: PlatformSlug,
    installedPackages: Set<String>,
  ): LaunchTarget? {
    for (emulator in EmulatorRegistry.forPlatform(platform)) {
      val packageName = installedBuildOf(emulator, installedPackages) ?: continue
      return LaunchTarget(packageName, emulator.recipeFor(packageName), emulator)
    }
    return null
  }

  private fun targetFor(choice: LaunchChoice): LaunchTarget {
    val emulator = EmulatorRegistry.forPackage(choice.packageName)
    return LaunchTarget(
      packageName = choice.packageName,
      recipe = choice.recipe ?: emulator?.recipeFor(choice.packageName) ?: LaunchRecipe.ViewRom,
      emulator = emulator,
      core = choice.core,
    )
  }

  /**
   * The installed package that is [emulator]: its own package if installed, else the first, by
   * name, of the installed builds its patterns match that aren't another known emulator.
   */
  private fun installedBuildOf(emulator: KnownEmulator, installed: Set<String>): String? =
    emulator.packageName.takeIf { it in installed }
      ?: installed
        .filter { emulator.matchesPattern(it) && EmulatorRegistry.forPackage(it) == emulator }
        .minOrNull()
}
