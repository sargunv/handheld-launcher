package dev.sargunv.handheld.gradle

import com.android.build.api.dsl.CommonExtension
import java.io.File
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/** The NDK mise.toml pins, in a directory named for its version. */
fun ProviderFactory.miseNdk(): Provider<String> = environmentVariable("ANDROID_NDK_HOME")

/** Builds and strips native code with [ndk], when it's set, instead of the SDK's default NDK. */
fun CommonExtension.useNdk(ndk: Provider<String>) {
  ndk.orNull?.let {
    ndkPath = it
    ndkVersion = File(it).name
  }
}
