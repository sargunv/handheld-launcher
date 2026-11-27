package dev.sargunv.handheld.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Puts the build's own task types, and JNA, on the classpath of a script that applies it. */
class BuildLogicPlugin : Plugin<Project> {
  override fun apply(target: Project) = Unit
}
