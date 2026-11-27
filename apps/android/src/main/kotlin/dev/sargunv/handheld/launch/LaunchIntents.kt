// Ported from Argosy (https://github.com/rommapp/argosy-launcher), GPL-3.0.

package dev.sargunv.handheld.launch

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.launch.LaunchUri

/** Makes the content URIs a launch hands over. */
fun interface ContentUris {
  /** A content URI for the file at [path]; throws when the provider can't serve it. */
  fun forFile(path: String): Uri
}

/**
 * The intent for `startActivity` that carries this command, with its files' content URIs from
 * [contentUris]. The read grant flag covers the data and clip data URIs; a command builder puts the
 * ROM in clip data whenever it hands over a content URI, so extras that name it are covered too.
 */
internal fun LaunchCommand.toIntent(contentUris: ContentUris): Intent {
  fun uri(value: LaunchUri): Uri =
    when (value) {
      is LaunchUri.ProviderFile -> contentUris.forFile(value.path)
      is LaunchUri.Raw -> Uri.parse(value.value)
    }

  val intent = Intent(action)
  when (val activity = activity) {
    null -> intent.setPackage(packageName)
    else -> intent.component = ComponentName(packageName, activity)
  }
  categories.forEach(intent::addCategory)
  data?.let { data ->
    if (mimeType != null) intent.setDataAndType(uri(data), mimeType) else intent.data = uri(data)
  }
  for (extra in extras) {
    when (extra) {
      is LaunchExtra.StringValue -> intent.putExtra(extra.key, extra.value)
      is LaunchExtra.UriValue -> intent.putExtra(extra.key, uri(extra.uri))
      is LaunchExtra.UriString -> intent.putExtra(extra.key, uri(extra.uri).toString())
      is LaunchExtra.BooleanValue -> intent.putExtra(extra.key, extra.value)
      is LaunchExtra.StringArray -> intent.putExtra(extra.key, extra.values.toTypedArray())
    }
  }
  clipData?.let { intent.clipData = ClipData.newRawUri(null, uri(it)) }
  intent.addFlags(flags.intentFlags())
  return intent
}

/**
 * The arguments that run this command through `am start`, for [LaunchMethod.Shell] launches, to
 * execute directly without a shell. The command must be able to use the shell
 * ([LaunchCommand.canUseShell]).
 */
internal fun LaunchCommand.amStartArgv(): List<String> {
  require(canUseShell) { "a command with content URIs must go as an intent" }
  return buildList {
    add("/system/bin/am")
    add("start")
    add("-a")
    add(action)
    if (activity != null) {
      add("-n")
      add("$packageName/$activity")
    } else {
      add("-p")
      add(packageName)
    }
    for (category in categories) {
      add("-c")
      add(category)
    }
    data?.let {
      add("-d")
      add(raw(it))
    }
    mimeType?.let {
      add("-t")
      add(it)
    }
    val flagBits = flags.intentFlags()
    if (flagBits != 0) {
      add("-f")
      add("0x" + flagBits.toString(16))
    }
    for (extra in extras) {
      when (extra) {
        is LaunchExtra.StringValue -> addAll(listOf("--es", extra.key, extra.value))
        is LaunchExtra.BooleanValue -> addAll(listOf("--ez", extra.key, extra.value.toString()))
        is LaunchExtra.StringArray ->
          addAll(
            listOf("--esa", extra.key, extra.values.joinToString(",") { it.replace(",", "\\,") }),
          )
        is LaunchExtra.UriValue,
        is LaunchExtra.UriString -> error("unreachable: content URIs are refused above")
      }
    }
  }
}

private fun raw(uri: LaunchUri): String =
  when (uri) {
    is LaunchUri.Raw -> uri.value
    is LaunchUri.ProviderFile -> error("unreachable: content URIs are refused above")
  }

/** The `Intent.FLAG_*` bits for these flags. */
private fun Set<LaunchFlag>.intentFlags(): Int =
  fold(0) { bits, flag ->
    bits or
      when (flag) {
        LaunchFlag.NewTask -> Intent.FLAG_ACTIVITY_NEW_TASK
        LaunchFlag.ClearTask -> Intent.FLAG_ACTIVITY_CLEAR_TASK
        LaunchFlag.ClearTop -> Intent.FLAG_ACTIVITY_CLEAR_TOP
        LaunchFlag.NoHistory -> Intent.FLAG_ACTIVITY_NO_HISTORY
        LaunchFlag.GrantReadUriPermission -> Intent.FLAG_GRANT_READ_URI_PERMISSION
      }
  }
