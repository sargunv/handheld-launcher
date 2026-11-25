// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.launch.LaunchRecipe
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.jvm.JvmInline

/** A stable name for a [KnownEmulator], such as `duckstation`. */
@JvmInline
value class EmulatorId(val value: String) {
  override fun toString(): String = value
}

/**
 * An Android app this launcher knows how to start games in.
 *
 * [packagePatterns] match other builds of the same app, such as forks and nightly channels, where
 * `*` stands for any run of characters. A build matched only by pattern launches with [forkRecipe]
 * when it's set, since a fork can rename the activities [recipe] names. [drawsBothScreens] marks
 * apps that draw their own second screen: their game opens on the default display, and the app
 * takes the other one.
 */
data class KnownEmulator(
  val id: EmulatorId,
  val displayName: String,
  val packageName: String,
  val platforms: Set<PlatformSlug>,
  val recipe: LaunchRecipe,
  val packagePatterns: List<String> = emptyList(),
  val forkRecipe: LaunchRecipe? = null,
  val drawsBothScreens: Boolean = false,
  val downloadUrl: String? = null,
  val releases: ReleaseSource? = null,
) {
  /** Whether [packageName] is this app: its own package, or a build its patterns match. */
  fun matches(packageName: String): Boolean =
    packageName == this.packageName || matchesPattern(packageName)

  /** Whether [packageName] is another build of this app, matched by [packagePatterns]. */
  fun matchesPattern(packageName: String): Boolean = packagePatterns.any {
    globToRegex(it).matches(packageName)
  }

  /** The recipe that launches games in [packageName], one of this app's builds. */
  fun recipeFor(packageName: String): LaunchRecipe =
    if (packageName == this.packageName) recipe else forkRecipe ?: recipe

  private fun globToRegex(glob: String): Regex =
    Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
}

/** Where an emulator publishes its releases. */
sealed interface ReleaseSource {
  data class GitHub(val repo: String) : ReleaseSource

  data class Gitea(val baseUrl: String, val repo: String) : ReleaseSource

  data class GitLab(val baseUrl: String, val projectPath: String) : ReleaseSource
}
