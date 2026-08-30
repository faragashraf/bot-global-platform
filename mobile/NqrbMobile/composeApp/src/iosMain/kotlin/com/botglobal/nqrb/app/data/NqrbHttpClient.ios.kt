package com.botglobal.nqrb.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createNqrbHttpClient(): HttpClient = HttpClient(Darwin)
