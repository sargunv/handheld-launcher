package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * How an app wants a game handed to it: the shape of a [LaunchCommand] before a ROM fills it in.
 *
 * [data] and [extras] say where the ROM goes. [flags] never needs
 * [LaunchFlag.GrantReadUriPermission]: building a command adds it whenever the command hands over a
 * content URI. [grantRom] hands the ROM over by content URI as clip data even when the recipe
 * passes it only by path, for apps that may read it either way. [stopBeforeLaunch] marks apps that
 * ignore a launch while a previous session is still alive.
 */
data class LaunchRecipe(
  val action: String = IntentAction.VIEW,
  val activity: String? = null,
  val categories: List<String> = listOf(IntentCategory.DEFAULT),
  val data: RomData? = RomData.ProviderUri,
  val mimeType: String? = OCTET_STREAM,
  val extras: Map<String, ExtraValue> = emptyMap(),
  val flags: Set<LaunchFlag> = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask),
  val method: LaunchMethod = LaunchMethod.Intent,
  val grantRom: Boolean = false,
  val stopBeforeLaunch: Boolean = false,
) {
  companion object {
    const val OCTET_STREAM = "application/octet-stream"

    /** The usual Android hand-off: `VIEW` the ROM's content URI, and let the app open it. */
    val ViewRom: LaunchRecipe = LaunchRecipe()

    /** Open the app without a game, for apps that find their games themselves. */
    val OpenApp: LaunchRecipe =
      LaunchRecipe(
        action = IntentAction.MAIN,
        categories = listOf(IntentCategory.LAUNCHER),
        data = null,
        mimeType = null,
        flags = setOf(LaunchFlag.NewTask),
      )

    /** A launch for an app that takes the ROM in extras rather than as the intent's data. */
    fun withExtras(
      action: String,
      activity: String,
      extras: Map<String, ExtraValue>,
      data: RomData? = null,
      method: LaunchMethod = LaunchMethod.Intent,
    ): LaunchRecipe =
      LaunchRecipe(
        action = action,
        activity = activity,
        data = data,
        mimeType = null,
        extras = extras,
        flags = setOf(LaunchFlag.NewTask, LaunchFlag.ClearTask, LaunchFlag.NoHistory),
        method = method,
      )
  }
}

/** How a ROM rides in an intent's data. */
sealed interface RomData {
  /** A content URI from the app's FileProvider. */
  data object ProviderUri : RomData

  /** The ROM's absolute path, written as the URI. */
  data object Path : RomData

  /** `scheme://authority` followed by the ROM's absolute path. */
  data class Scheme(val scheme: String, val authority: String) : RomData
}

/** The value of one intent extra in a [LaunchRecipe]. */
sealed interface ExtraValue {
  /** The ROM's absolute path. */
  data object RomPath : ExtraValue

  /** The ROM's content URI, as a `Uri` extra. */
  data object RomUri : ExtraValue

  /**
   * The ROM's content URI as a string. An `.m3u` playlist goes by path instead, since the app
   * resolves the discs it lists relative to it.
   */
  data object RomUriString : ExtraValue

  /**
   * The ROM's path when the installed build is [minBuild] or newer, and its content URI string
   * below that or when the build can't be read. The build is the number after the dash in a version
   * name such as `0.1-10234`.
   */
  data class RomPathSinceBuild(val minBuild: Int) : ExtraValue

  /**
   * Text with placeholders: `{package}` for the target package, `{storage}` for the primary
   * external storage root, and `{core}` for the libretro core's library name, such as
   * `mesen_libretro`.
   */
  data class Text(val template: String) : ExtraValue

  data class Bool(val value: Boolean) : ExtraValue

  /** The app's own name for the game's platform; the extra is left out for platforms not listed. */
  data class PlatformName(val names: Map<PlatformSlug, String>) : ExtraValue

  /**
   * Vita3K's start arguments, `-r <title id>`. The extra is left out when no title id is known, and
   * the app opens to its library.
   */
  data object VitaTitleArgs : ExtraValue
}
