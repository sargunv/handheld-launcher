plugins { `kotlin-dsl` }

dependencies {
  implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation(
    "com.mikepenz.aboutlibraries.plugin:aboutlibraries-plugin:${libs.versions.aboutlibraries.get()}",
  )
}
