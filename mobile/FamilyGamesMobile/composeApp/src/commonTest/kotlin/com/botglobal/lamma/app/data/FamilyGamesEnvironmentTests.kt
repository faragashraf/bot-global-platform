package com.botglobal.lamma.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FamilyGamesEnvironmentTests {
    @Test
    fun publicEnvironmentComposesApiInvitationAndRealtimeRoutesFromOneBase() {
        val environment = FamilyGamesEnvironment.from("https://botglobalservice.com/backend/")

        assertEquals("https://botglobalservice.com/backend", environment.apiBaseUrl)
        assertEquals(
            "https://botglobalservice.com/backend/api/games/invitations/resolve",
            environment.endpoint("/api/games/invitations/resolve"),
        )
        assertEquals("https://botglobalservice.com/backend/hubs/games", environment.gamesHubUrl)
    }

    @Test
    fun debugLanOverrideRemainsSupported() {
        val environment = FamilyGamesEnvironment.from("http://192.168.1.25:5062/")

        assertEquals("http://192.168.1.25:5062", environment.apiBaseUrl)
        assertEquals("http://192.168.1.25:5062/hubs/games", environment.gamesHubUrl)
    }

    @Test
    fun environmentRejectsNonHttpAndAmbiguousBaseValues() {
        assertFailsWith<IllegalArgumentException> { FamilyGamesEnvironment.from("localhost:5062") }
        assertFailsWith<IllegalArgumentException> {
            FamilyGamesEnvironment.from("https://botglobalservice.com/backend?source=release")
        }
        assertFailsWith<IllegalArgumentException> {
            FamilyGamesEnvironment.from("https://botglobalservice.com/backend/../api")
        }
    }
}
