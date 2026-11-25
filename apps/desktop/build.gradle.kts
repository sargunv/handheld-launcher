plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

dependencies {
  implementation(project(":ui"))
  implementation(project(":data"))
  implementation(compose.desktop.currentOs)
}

compose.desktop { application { mainClass = "dev.sargunv.handheld.desktop.MainKt" } }
