package dev.sargunv.handheld.domain.platform

import kotlin.jvm.JvmInline

/** A platform as RomM names it, such as `ps2` or `ngc`. */
@JvmInline
value class PlatformSlug(val value: String) {
  override fun toString(): String = value
}

/**
 * A platform the library can hold.
 *
 * [aliases] are other names for the same platform, such as the folder names ES-DE and Batocera use,
 * so a slug from either source finds it. [runsAs] names the platform whose emulators and cores run
 * this one, for regional twins such as the Famicom and the NES; null when it runs as itself.
 */
data class Platform(
  val slug: PlatformSlug,
  val displayName: String,
  val aliases: Set<String> = emptySet(),
  val runsAs: PlatformSlug? = null,
) {
  /** The platform whose emulators and cores run this one. */
  val emulatedAs: PlatformSlug
    get() = runsAs ?: slug
}
