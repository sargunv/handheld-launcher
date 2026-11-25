// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.domain.launch.android

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchUri

/**
 * Makes the content URIs a launch hands over. The app implements it with its FileProvider, as
 * `FileProvider.getUriForFile(context, authority, File(path))`, since it owns the provider and its
 * paths configuration.
 */
fun interface ContentUris {
  /** A content URI for the file at [path]; throws when the provider can't serve it. */
  fun forFile(path: String): Uri
}

/** Turns [LaunchCommand]s into intents for `startActivity`. */
object LaunchIntents {
  /** The `Intent.FLAG_*` bits for [flags]. */
  fun flagsOf(flags: Set<LaunchFlag>): Int =
    flags.fold(0) { bits, flag ->
      bits or
        when (flag) {
          LaunchFlag.NewTask -> Intent.FLAG_ACTIVITY_NEW_TASK
          LaunchFlag.ClearTask -> Intent.FLAG_ACTIVITY_CLEAR_TASK
          LaunchFlag.ClearTop -> Intent.FLAG_ACTIVITY_CLEAR_TOP
          LaunchFlag.SingleTop -> Intent.FLAG_ACTIVITY_SINGLE_TOP
          LaunchFlag.NoHistory -> Intent.FLAG_ACTIVITY_NO_HISTORY
          LaunchFlag.GrantReadUriPermission -> Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

  /**
   * The intent that carries [command], with its files' content URIs from [contentUris]. The read
   * grant flag covers the data and clip data URIs; a command builder puts the ROM in clip data
   * whenever it hands over a content URI, so extras that name it are covered too.
   */
  fun toIntent(command: LaunchCommand, contentUris: ContentUris): Intent {
    fun uri(value: LaunchUri): Uri =
      when (value) {
        is LaunchUri.ProviderFile -> contentUris.forFile(value.path)
        is LaunchUri.Raw -> Uri.parse(value.value)
      }

    val intent = Intent(command.action)
    if (command.activity != null) {
      intent.component = ComponentName(command.packageName, command.activity)
    } else {
      intent.setPackage(command.packageName)
    }
    command.categories.forEach(intent::addCategory)
    command.data?.let { data ->
      if (command.mimeType != null) intent.setDataAndType(uri(data), command.mimeType)
      else intent.data = uri(data)
    }
    for (extra in command.extras) {
      when (extra) {
        is LaunchExtra.StringValue -> intent.putExtra(extra.key, extra.value)
        is LaunchExtra.UriValue -> intent.putExtra(extra.key, uri(extra.uri))
        is LaunchExtra.UriString -> intent.putExtra(extra.key, uri(extra.uri).toString())
        is LaunchExtra.BooleanValue -> intent.putExtra(extra.key, extra.value)
        is LaunchExtra.StringArray -> intent.putExtra(extra.key, extra.values.toTypedArray())
      }
    }
    command.clipData?.let { intent.clipData = ClipData.newRawUri(null, uri(it)) }
    intent.addFlags(flagsOf(command.flags))
    return intent
  }
}
