package com.ashraffarag.sentricam.communication.signalr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRConfigurationProviderTest {
    @Test
    fun releaseConfigurationBuildsHttpsHubUrl() {
        val result = SignalRConfigurationProvider(
            FakeSignalRServerConfiguration("https://server.example/"),
        ).load()

        assertEquals("https://server.example/hubs/device", result.getOrThrow().hubUrl)
    }

    @Test
    fun releaseConfigurationRejectsCleartextWhileDebugAllowsIt() {
        val release = SignalRConfigurationProvider(
            FakeSignalRServerConfiguration("http://192.168.1.20:5173/"),
        ).load()
        val debug = SignalRConfigurationProvider(
            FakeSignalRServerConfiguration("http://192.168.1.20:5173/", isDebug = true),
        ).load()

        assertTrue(release.isFailure)
        assertEquals("http://192.168.1.20:5173/hubs/device", debug.getOrThrow().hubUrl)
    }
}
