package dev.sargunv.handheld.domain.launch.android

import dev.sargunv.handheld.domain.launch.LaunchCommand
import dev.sargunv.handheld.domain.launch.LaunchExtra
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.launch.LaunchUri

/** Turns [LaunchCommand]s into `am start` invocations, for [LaunchMethod.Shell] launches. */
object ShellLaunch {
  private const val AM = "/system/bin/am"

  /**
   * The arguments that run [command] through `am start`, to execute directly without a shell.
   * [command] must be able to use the shell ([LaunchCommand.canUseShell]).
   */
  fun argv(command: LaunchCommand): List<String> {
    require(command.canUseShell) { "a command with content URIs must go as an intent" }
    return buildList {
      add(AM)
      add("start")
      add("-a")
      add(command.action)
      if (command.activity != null) {
        add("-n")
        add("${command.packageName}/${command.activity}")
      } else {
        add("-p")
        add(command.packageName)
      }
      for (category in command.categories) {
        add("-c")
        add(category)
      }
      command.data?.let {
        add("-d")
        add(raw(it))
      }
      command.mimeType?.let {
        add("-t")
        add(it)
      }
      val flags = LaunchIntents.flagsOf(command.flags)
      if (flags != 0) {
        add("-f")
        add("0x" + flags.toString(16))
      }
      for (extra in command.extras) {
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

  /** [argv] as one line for `sh -c`, with every argument quoted. */
  fun commandLine(command: LaunchCommand): String = argv(command).joinToString(" ") { quote(it) }

  private fun raw(uri: LaunchUri): String =
    when (uri) {
      is LaunchUri.Raw -> uri.value
      is LaunchUri.ProviderFile -> error("unreachable: content URIs are refused above")
    }

  private fun quote(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"
}
