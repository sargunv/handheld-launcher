package dev.sargunv.handheld.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout

/**
 * A client on [engine] that returns error responses instead of throwing, and gives up on a
 * connection that stays silent for [socketTimeoutMillis].
 */
internal fun httpClient(
  engine: HttpClientEngine,
  socketTimeoutMillis: Long = 60_000,
  configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
  HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      this.socketTimeoutMillis = socketTimeoutMillis
    }
    configure()
  }
