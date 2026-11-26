package dev.sargunv.handheld.ui

import dev.sargunv.handheld.data.auth.DeviceDescription
import kotlin.io.path.createTempDirectory
import kotlinx.io.files.Path

internal val TestDevice = DeviceDescription("install-1", "Test device", "test", null)

/** A new, empty directory for covers. */
internal fun testCoverDir() = Path(createTempDirectory("handheld-covers").toString())
