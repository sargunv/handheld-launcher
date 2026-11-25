// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.launch

/**
 * Everything needed to start a game in another app, as plain data. Android turns it into an intent
 * or an `am start` command; nothing here touches the system.
 *
 * [activity] is a class name inside [packageName], or null to let the system pick the package's
 * activity that matches [action], [categories] and [data]. Every [LaunchUri.ProviderFile] in the
 * command is a file the app must grant [packageName] read access to; [readGrants] lists them.
 */
data class LaunchCommand(
  val packageName: String,
  val activity: String? = null,
  val action: String,
  val categories: List<String> = emptyList(),
  val data: LaunchUri? = null,
  val mimeType: String? = null,
  val extras: List<LaunchExtra> = emptyList(),
  val flags: Set<LaunchFlag> = emptySet(),
  val clipData: LaunchUri? = null,
  val method: LaunchMethod = LaunchMethod.Intent,
  val stopBeforeLaunch: Boolean = false,
) {
  /** The files this command hands over by content URI, each to be granted once. */
  val readGrants: List<LaunchUri.ProviderFile>
    get() {
      val uris = buildSet {
        (data as? LaunchUri.ProviderFile)?.let(::add)
        for (extra in extras) {
          when (extra) {
            is LaunchExtra.UriValue -> add(extra.uri)
            is LaunchExtra.UriString -> add(extra.uri)
            else -> {}
          }
        }
        (clipData as? LaunchUri.ProviderFile)?.let(::add)
      }
      return uris.toList()
    }

  /**
   * Whether `am start` can carry this command. It can't hand over content URIs, since only the app
   * that owns the provider can grant them, so a command with any must go as an intent.
   */
  val canUseShell: Boolean
    get() = readGrants.isEmpty()
}

/** A URI in a launch command. */
sealed interface LaunchUri {
  /**
   * A content URI for the file at [path], served by the app's FileProvider and granted to the
   * target for reading.
   */
  data class ProviderFile(val path: String) : LaunchUri

  /** A URI passed through as written, such as a bare file path or a custom scheme. */
  data class Raw(val value: String) : LaunchUri
}

/** An intent extra in a launch command. */
sealed interface LaunchExtra {
  val key: String

  data class StringValue(override val key: String, val value: String) : LaunchExtra

  /** A content URI for a file, put as a `Uri` extra. */
  data class UriValue(override val key: String, val uri: LaunchUri.ProviderFile) : LaunchExtra

  /** A content URI for a file, put as its string form. */
  data class UriString(override val key: String, val uri: LaunchUri.ProviderFile) : LaunchExtra

  data class BooleanValue(override val key: String, val value: Boolean) : LaunchExtra

  data class StringArray(override val key: String, val values: List<String>) : LaunchExtra
}

/** The intent flags a launch can set. */
enum class LaunchFlag {
  NewTask,
  ClearTask,
  ClearTop,
  SingleTop,
  NoHistory,
  GrantReadUriPermission,
}

/** How a launch command reaches the target app. */
enum class LaunchMethod {
  /** `startActivity` with an intent. */
  Intent,

  /**
   * `am start` from the app's own process. Emulators that hold all-files access read paths more
   * reliably this way, since no URI grant is involved. A command that can't use the shell
   * ([LaunchCommand.canUseShell]) goes as an intent instead.
   */
  Shell,
}

/** Intent actions launch recipes use. */
object IntentAction {
  const val VIEW = "android.intent.action.VIEW"
  const val MAIN = "android.intent.action.MAIN"
}

/** Intent categories launch recipes use. */
object IntentCategory {
  const val DEFAULT = "android.intent.category.DEFAULT"
  const val LAUNCHER = "android.intent.category.LAUNCHER"
}
