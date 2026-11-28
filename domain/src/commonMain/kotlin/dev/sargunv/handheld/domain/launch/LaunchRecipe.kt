// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * How an app wants a game handed to it: the shape of a [LaunchCommand] before a ROM fills it in.
 *
 * [data] and [extras] say where the ROM goes. [grantRom] hands the ROM over by content URI as clip
 * data even when the recipe passes it only by path, for apps that may read it either way. A command
 * that hands over a content URI goes as an intent, whatever [method] says.
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
) {
  /** Whether the app loads a libretro core, which a launch names. */
  val loadsCore: Boolean
    get() = extras.values.any { it is ExtraValue.Text && "{core}" in it.template }

  /**
   * The command that launches the game at [romPath] on [platform] in [packageName] this way, or
   * null when the recipe [loadsCore] and [core] is null. [installedVersionName] is the app's
   * version name, for recipes that depend on its build.
   */
  fun commandFor(
    packageName: String,
    romPath: String,
    platform: PlatformSlug,
    core: LibretroCore?,
    installedVersionName: String?,
  ): LaunchCommand? {
    val rom = LaunchUri.ProviderFile(romPath)
    val coreLibrary = if (loadsCore) core?.androidLibrary ?: return null else null
    val dataUri =
      when (val romData = data) {
        null -> null
        RomData.ProviderUri -> rom
        RomData.Path -> LaunchUri.Raw(romPath)
        RomData.FileUri -> LaunchUri.Raw("file://${encodePath(romPath)}")
        is RomData.Scheme ->
          LaunchUri.Raw("${romData.scheme}://${romData.authority}${encodePath(romPath)}")
      }
    val byPath = romPath.endsWith(".m3u", ignoreCase = true)
    val filled = extras.mapNotNull { (key, value) ->
      when (value) {
        ExtraValue.RomPath -> LaunchExtra.StringValue(key, romPath)
        ExtraValue.RomUri -> LaunchExtra.UriValue(key, rom)
        ExtraValue.RomUriString ->
          if (byPath) LaunchExtra.StringValue(key, romPath) else LaunchExtra.UriString(key, rom)
        is ExtraValue.RomPathSinceBuild -> {
          val build = installedVersionName?.let(::buildNumberOf) ?: 0
          if (build >= value.minBuild || byPath) LaunchExtra.StringValue(key, romPath)
          else LaunchExtra.UriString(key, rom)
        }
        is ExtraValue.Text ->
          LaunchExtra.StringValue(key, fill(value.template, packageName, coreLibrary))
        is ExtraValue.Bool -> LaunchExtra.BooleanValue(key, value.value)
        is ExtraValue.PlatformName ->
          (value.names[platform] ?: value.names[platform.emulatedAs])?.let {
            LaunchExtra.StringValue(key, it)
          }
        ExtraValue.VitaTitleArgs ->
          vitaTitleId(romPath.substringAfterLast('/'))?.let {
            LaunchExtra.StringArray(key, listOf("-r", it))
          }
      }
    }
    val handsOverUri =
      grantRom ||
        dataUri is LaunchUri.ProviderFile ||
        filled.any { it is LaunchExtra.UriValue || it is LaunchExtra.UriString }
    return LaunchCommand(
      packageName = packageName,
      activity = activity,
      action = action,
      categories = categories,
      data = dataUri,
      mimeType = mimeType.takeIf { dataUri != null },
      extras = filled,
      flags = flags,
      clipData = rom.takeIf { handsOverUri },
      // `am start` can't hand over content URIs.
      method = if (handsOverUri) LaunchMethod.Intent else method,
    )
  }

  companion object {
    const val OCTET_STREAM = "application/octet-stream"

    /** The usual Android hand-off: `VIEW` the ROM's content URI, and let the app open it. */
    val ViewRom: LaunchRecipe = LaunchRecipe()

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

    // Android's regex engine, ICU, needs the closing brace escaped too.
    private val placeholder = Regex("""\{(\w+)\}""")
    private val buildNumber = Regex("""^\d+\.\d+-(\d+)""")
    private val bracketedTitleId = Regex("""\[([A-Z]{4}\d{5})]""")
    private val leadingTitleId = Regex("""^([A-Z]{4}\d{5})""")

    /** The primary external storage root for Android's first user. */
    private const val PRIMARY_STORAGE = "/storage/emulated/0"

    private const val HEX = "0123456789ABCDEF"

    /** The build number in a version name such as `0.1-10234`, or null when it has none. */
    private fun buildNumberOf(versionName: String): Int? =
      buildNumber.find(versionName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * A PlayStation Vita title id, such as `PCSE00082`, in brackets anywhere in [fileName], or at
     * its start; null when it has none.
     */
    private fun vitaTitleId(fileName: String): String? =
      (bracketedTitleId.find(fileName) ?: leadingTitleId.find(fileName))?.groupValues?.get(1)

    /**
     * [path] with every character but `/` and URI-unreserved ones percent-encoded as UTF-8, as
     * Android's `Uri.Builder.path` writes it, so a `#`, `?` or `%` in a file name stays in the
     * path.
     */
    private fun encodePath(path: String): String = buildString {
      for (byte in path.encodeToByteArray()) {
        val char = (byte.toInt() and 0xff).toChar()
        if (char.isLetterOrDigit() && char.code < 0x80 || char in "/_-!.~'()*") {
          append(char)
        } else {
          append('%')
          append(HEX[byte.toInt() shr 4 and 0xf])
          append(HEX[byte.toInt() and 0xf])
        }
      }
    }

    private fun fill(template: String, packageName: String, core: String?) =
      placeholder.replace(template) { match ->
        when (val name = match.groupValues[1]) {
          "package" -> packageName
          "storage" -> PRIMARY_STORAGE
          "core" -> checkNotNull(core) { "core placeholder without a core" }
          else -> error("unknown placeholder {$name}")
        }
      }
  }
}

/** How a ROM rides in an intent's data. */
sealed interface RomData {
  /** A content URI from the app's FileProvider. */
  data object ProviderUri : RomData

  /**
   * The ROM's absolute path, written as the URI unencoded, for apps that read the URI's string as a
   * path. Android keeps that string as written, so any file name reaches the app intact.
   */
  data object Path : RomData

  /**
   * A `file://` URI for the ROM, with its path percent-encoded, for apps that read the URI's
   * decoded path. Android refuses to start an activity with one from an app's own process, so a
   * recipe that uses it goes through [LaunchMethod.Shell].
   */
  data object FileUri : RomData

  /** `scheme://authority` followed by the ROM's absolute path, percent-encoded. */
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
