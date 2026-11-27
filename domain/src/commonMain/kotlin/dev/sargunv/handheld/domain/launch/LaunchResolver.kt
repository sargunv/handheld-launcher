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

/** The outcome of picking the app for a game. */
sealed interface LaunchResolution {
  data class Resolved(val target: LaunchTarget, val rule: LaunchRule) : LaunchResolution

  /**
   * The game runs in the built-in session. [core] is the libretro core the rule named, by library
   * name, or null for the platform's recommended core.
   */
  data class BuiltIn(val rule: LaunchRule, val core: String? = null) : LaunchResolution

  /**
   * No rule named an installed app, and no known emulator for [platform] is installed.
   * [suggestions] are the known emulators that would run it, best first.
   */
  data class NoEmulatorInstalled(
    val platform: PlatformSlug,
    val suggestions: List<KnownEmulator>,
  ) : LaunchResolution
}

/**
 * Picks the app a game launches in: the level of [RuleChain.explain] that wins. A rule may name any
 * app or the built-in session. One whose app isn't installed is skipped, as is one that picks the
 * built-in session for a platform no libretro core runs.
 */
object LaunchResolver {
  fun resolve(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): LaunchResolution {
    val winner =
      RuleChain.explain(subject, rules, installedPackages).firstOrNull {
        it.status == RuleStatus.Wins
      }
    val choice =
      winner?.choice
        ?: return LaunchResolution.NoEmulatorInstalled(
          subject.platform,
          EmulatorRegistry.forPlatform(subject.platform),
        )
    return if (choice.isBuiltIn) {
      LaunchResolution.BuiltIn(winner.rule, choice.core)
    } else {
      LaunchResolution.Resolved(targetFor(choice), winner.rule)
    }
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
}
