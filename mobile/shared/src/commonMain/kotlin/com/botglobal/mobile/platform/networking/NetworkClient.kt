package com.botglobal.mobile.platform.networking

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

data class NetworkClientConfiguration(
    val connectTimeoutMillis: Long = 15_000,
    val requestTimeoutMillis: Long = 18_000,
    val socketTimeoutMillis: Long = 18_000,
) {
    init {
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive." }
        require(requestTimeoutMillis > 0) { "Request timeout must be positive." }
        require(socketTimeoutMillis > 0) { "Socket timeout must be positive." }
    }
}

fun createNetworkClient(
    configuration: NetworkClientConfiguration = NetworkClientConfiguration(),
): HttpClient = createPlatformHttpClient().config {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = configuration.connectTimeoutMillis
        requestTimeoutMillis = configuration.requestTimeoutMillis
        socketTimeoutMillis = configuration.socketTimeoutMillis
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
}

internal expect fun createPlatformHttpClient(): HttpClient
