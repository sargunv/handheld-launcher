plugins { `kotlin-dsl` }

dependencies { implementation(libs.jna) }

gradlePlugin {
  plugins {
    register("buildLogic") {
      id = "handheld.build-logic"
      implementationClass = "dev.sargunv.handheld.gradle.BuildLogicPlugin"
    }
  }
}
