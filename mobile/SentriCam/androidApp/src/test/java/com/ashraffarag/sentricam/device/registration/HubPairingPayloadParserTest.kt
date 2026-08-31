package com.ashraffarag.sentricam.device.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPairingPayloadParserTest {
    @Test
    fun parsesVersionedPayloadWithoutExposingManualNetworkFields() {
        val result = HubPairingPayloadParser.parse(
            "sentricam://pair?v=1&hub=https%3A%2F%2F192.168.1.20%3A5173%2F&code=0123456789abcdef0123456789abcdef&fp=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertTrue(result is RegistrationCallResult.Success)
        val payload = (result as RegistrationCallResult.Success).value
        assertEquals("https://192.168.1.20:5173/", payload.hubBaseUrl)
        assertEquals("0123456789abcdef0123456789abcdef", payload.pairingCode)
        assertEquals("1", payload.protocolVersion)
        assertEquals("a".repeat(64), payload.hubFingerprint)
    }

    @Test
    fun rejectsUnknownVersionAndMalformedCode() {
        val result = HubPairingPayloadParser.parse(
            "sentricam://pair?v=2&hub=https%3A%2F%2Fhub.local%2F&code=short&fp=bad",
        )

        assertTrue(result is RegistrationCallResult.Failure)
        assertTrue((result as RegistrationCallResult.Failure).failure is RegistrationFailure.InvalidServerUrl)
    }
}
