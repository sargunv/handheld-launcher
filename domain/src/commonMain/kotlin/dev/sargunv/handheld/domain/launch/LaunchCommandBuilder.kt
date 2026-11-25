package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * The game-specific facts a [LaunchRecipe] needs.
 *
 * [core] is the libretro core's library name, such as `snes9x_libretro`, for recipes that load one;
 * null picks [LibretroCores.recommendedFor] the platform. [titleId] is the game's title id where
 * the platform has one; null derives it from the file name where the recipe needs it.
 * [installedVersionName] is the target app's version name, for recipes that depend on its build.
 */
data class LaunchInput(
  val romPath: String,
  val platform: PlatformSlug,
  val core: String? = null,
  val titleId: String? = null,
  val installedVersionName: String? = null,
  val storageRoot: String = PRIMARY_STORAGE,
) {
  companion object {
    /** The primary external storage root for Android's first user. */
    const val PRIMARY_STORAGE = "/storage/emulated/0"
  }
}

/** The outcome of filling a [LaunchRecipe] with a game. */
sealed interface LaunchBuild {
  data class Ready(val command: LaunchCommand) : LaunchBuild

  /** The recipe loads a libretro core, and none was given or is recommended for [platform]. */
  data class MissingCore(val platform: PlatformSlug) : LaunchBuild
}

/** Fills [LaunchRecipe]s with games. */
object LaunchCommandBuilder {
  private val placeholder = Regex("""\{(\w+)}""")
  private val buildNumber = Regex("""^\d+\.\d+-(\d+)""")

  /** The placeholders [ExtraValue.Text] understands. */
  val placeholders: Set<String> = setOf("package", "storage", "core")

  /** The command that launches [input] in [packageName] the way [recipe] describes. */
  fun build(recipe: LaunchRecipe, packageName: String, input: LaunchInput): LaunchBuild {
    val rom = LaunchUri.ProviderFile(input.romPath)
    val needsCore = recipe.extras.values.any { it is ExtraValue.Text && "{core}" in it.template }
    val core =
      if (needsCore) {
        input.core
          ?: LibretroCores.recommendedFor(input.platform)?.androidLibrary
          ?: return LaunchBuild.MissingCore(input.platform)
      } else {
        null
      }

    val data =
      when (val romData = recipe.data) {
        null -> null
        RomData.ProviderUri -> rom
        RomData.Path -> LaunchUri.Raw(input.romPath)
        is RomData.Scheme ->
          LaunchUri.Raw("${romData.scheme}://${romData.authority}${encodePath(input.romPath)}")
      }

    val extras =
      recipe.extras.mapNotNull { (key, value) ->
        when (value) {
          ExtraValue.RomPath -> LaunchExtra.StringValue(key, input.romPath)
          ExtraValue.RomUri -> LaunchExtra.UriValue(key, rom)
          ExtraValue.RomUriString ->
            if (input.romPath.endsWith(".m3u", ignoreCase = true)) {
              LaunchExtra.StringValue(key, input.romPath)
            } else {
              LaunchExtra.UriString(key, rom)
            }
          is ExtraValue.RomPathSinceBuild -> {
            val build = input.installedVersionName?.let(::buildNumberOf) ?: 0
            if (build >= value.minBuild || input.romPath.endsWith(".m3u", ignoreCase = true)) {
              LaunchExtra.StringValue(key, input.romPath)
            } else {
              LaunchExtra.UriString(key, rom)
            }
          }
          is ExtraValue.Text ->
            LaunchExtra.StringValue(key, fill(value.template, packageName, input, core))
          is ExtraValue.Bool -> LaunchExtra.BooleanValue(key, value.value)
          is ExtraValue.PlatformName ->
            (value.names[input.platform] ?: value.names[PlatformCatalog.emulatedAs(input.platform)])
              ?.let { LaunchExtra.StringValue(key, it) }
          ExtraValue.VitaTitleArgs ->
            (input.titleId ?: VitaTitleId.fromFileName(input.romPath.substringAfterLast('/')))
              ?.let { LaunchExtra.StringArray(key, listOf("-r", it)) }
        }
      }

    val handsOverUri =
      recipe.grantRom ||
        data is LaunchUri.ProviderFile ||
        extras.any { it is LaunchExtra.UriValue || it is LaunchExtra.UriString }
    val command =
      LaunchCommand(
        packageName = packageName,
        activity = recipe.activity,
        action = recipe.action,
        categories = recipe.categories,
        data = data,
        mimeType = recipe.mimeType.takeIf { data != null },
        extras = extras,
        flags =
          if (handsOverUri) recipe.flags + LaunchFlag.GrantReadUriPermission else recipe.flags,
        clipData = rom.takeIf { handsOverUri },
        method = recipe.method,
        stopBeforeLaunch = recipe.stopBeforeLaunch,
      )
    return LaunchBuild.Ready(command)
  }

  /** The build number in a version name such as `0.1-10234`, or null when it has none. */
  fun buildNumberOf(versionName: String): Int? =
    buildNumber.find(versionName)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * [path] with every character but `/` and URI-unreserved ones percent-encoded as UTF-8, as
   * Android's `Uri.Builder.path` writes it, so a `#`, `?` or `%` in a file name stays in the path.
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

  private const val HEX = "0123456789ABCDEF"

  private fun fill(template: String, packageName: String, input: LaunchInput, core: String?) =
    placeholder.replace(template) { match ->
      when (val name = match.groupValues[1]) {
        "package" -> packageName
        "storage" -> input.storageRoot
        "core" -> checkNotNull(core) { "core placeholder without a core" }
        else -> error("unknown placeholder {$name}")
      }
    }
}

/** Reads PlayStation Vita title ids, such as `PCSE00082`, from file names. */
object VitaTitleId {
  private val bracketed = Regex("""\[([A-Z]{4}\d{5})]""")
  private val leading = Regex("""^([A-Z]{4}\d{5})""")

  /** The title id in brackets anywhere in [fileName], or at its start; null when it has none. */
  fun fromFileName(fileName: String): String? =
    (bracketed.find(fileName) ?: leading.find(fileName))?.groupValues?.get(1)
}
