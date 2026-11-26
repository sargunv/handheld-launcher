package dev.sargunv.handheld.domain.emulator

/**
 * A command for a device's root service, which runs it as root through a shell. It's one of two
 * fixed forms, each with an app's package name checked to hold only letters, digits, `_` and `.`,
 * so nothing else can reach the shell: [isRunning] and [forceStop].
 */
class EmulatorCommand private constructor(val line: String) {
  override fun toString(): String = line

  companion object {
    private val PACKAGE_NAME = Regex("[a-zA-Z0-9_.]+")

    /** Whether [packageName] passes the check the commands make. */
    fun isPackageName(packageName: String): Boolean =
      packageName.length <= 255 && PACKAGE_NAME.matches(packageName)

    /** Lists the process ids of [packageName]'s main process: none when it isn't running. */
    fun isRunning(packageName: String): EmulatorCommand =
      EmulatorCommand("pidof ${checked(packageName)}")

    /** Stops [packageName] at once, without the app's own exit. */
    fun forceStop(packageName: String): EmulatorCommand =
      EmulatorCommand("am force-stop ${checked(packageName)}")

    private fun checked(packageName: String): String {
      require(isPackageName(packageName)) { "Not a package name: $packageName" }
      return packageName
    }
  }
}
