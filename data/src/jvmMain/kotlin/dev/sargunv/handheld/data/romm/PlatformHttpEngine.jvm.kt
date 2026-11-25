package dev.sargunv.handheld.data.romm

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java

actual fun platformHttpEngine(): HttpClientEngine = Java.create()
