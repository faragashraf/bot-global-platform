package com.botglobal.nqrb.presence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NqrbPresenceSerializationTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun lease_requests_and_responses_use_generated_serializers() {
        assertEquals("{\"leaseId\":\"lease-a\"}", json.encodeToString(PresenceLeaseRequest("lease-a")))

        val decoded = json.decodeFromString<PresenceLeaseDto>(
            """{"customToken":"token","databaseUrl":"https://demo.firebaseio.com/","connectionPath":"presenceConnections/u/l/c","leaseId":"lease-a","expiresAtUtc":"2026-10-09T12:01:30Z","heartbeatSeconds":20,"freshnessSeconds":40,"future":"ignored"}""",
        )

        assertEquals("lease-a", decoded.leaseId)
        assertEquals(20, decoded.heartbeatSeconds)
        assertEquals("presenceConnections/u/l/c", decoded.connectionPath)
    }
}
