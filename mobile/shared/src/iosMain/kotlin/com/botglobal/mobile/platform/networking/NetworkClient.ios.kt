package com.botglobal.mobile.platform.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin)
