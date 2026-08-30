package com.botglobal.nqrb.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createNqrbHttpClient(): HttpClient = HttpClient(OkHttp)
