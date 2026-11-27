package dev.sargunv.handheld.domain.platform

import kotlin.jvm.JvmInline

/** A platform as RomM names it, such as `ps2` or `ngc`. */
@JvmInline
value class PlatformSlug(val value: String) {
  override fun toString(): String = value
}
