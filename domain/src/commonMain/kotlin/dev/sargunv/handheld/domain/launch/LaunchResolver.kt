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
  /** The command that launches [input] here, with this target's [core] unless [input] names one. */
  fun commandFor(input: LaunchInput): LaunchBuild =
    LaunchCommandBuilder.build(recipe, packageName, input.copy(core = input.core ?: core))

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
 * that applies, then its platform's default, then the best installed known emulator. A rule may
 * name any app, and one whose app isn't installed is skipped and reported.
 */
object LaunchResolver {
  fun resolve(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): LaunchResolution {
    val candidates = buildList {
      rules.gameOverrides[subject.gameId]?.let { add(LaunchRule.GameOverride to it) }
      rules.collectionRules
        .filter { it.collection in subject.collections }
        .filter { it.platform == null || it.platform == subject.platform }
        .forEach { add(LaunchRule.CollectionOverride(it.collection) to it.choice) }
      rules.platformDefaults[subject.platform]?.let { add(LaunchRule.PlatformDefault to it) }
    }

    val skipped = mutableListOf<SkippedChoice>()
    for ((rule, choice) in candidates) {
      if (choice.packageName in installedPackages) {
        return LaunchResolution.Resolved(targetFor(choice), rule, skipped.toList())
      }
      skipped += SkippedChoice(rule, choice.packageName)
    }

    val known = EmulatorRegistry.forPlatform(subject.platform)
    for (emulator in known) {
      val packageName = installedBuildOf(emulator, installedPackages) ?: continue
      val target = LaunchTarget(packageName, emulator.recipeFor(packageName), emulator)
      return LaunchResolution.Resolved(target, LaunchRule.FirstInstalled, skipped.toList())
    }
    return LaunchResolution.NoEmulatorInstalled(subject.platform, known, skipped.toList())
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
