package dev.sargunv.handheld.data.romm

import io.ktor.client.engine.HttpClientEngine

/** A new HTTP engine of the kind this platform uses. */
expect fun platformHttpEngine(): HttpClientEngine
