plugins {
  id("handheld.kmp-library")
}

kotlin { sourceSets { commonTest.dependencies { implementation(libs.kotlin.test) } } }
