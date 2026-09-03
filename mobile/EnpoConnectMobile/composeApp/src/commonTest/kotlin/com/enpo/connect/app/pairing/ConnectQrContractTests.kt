package com.enpo.connect.app.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectQrContractTests {
    @Test
    fun currentOpaqueConnectV2FormatIsAcceptedAndTrimmed() {
        val candidate = "A".repeat(43)
        val result = assertIs<ConnectQrValidation.Valid>(
            ConnectQrContract.validate("  $candidate\n"),
        )

        assertEquals(candidate, result.token.value)
        assertEquals("<redacted>", result.token.toString())
        assertFalse(candidate in result.toString())
    }

    @Test
    fun malformedWhitespaceAndUndersizedValuesAreRejected() {
        listOf(
            "",
            "   ",
            "short",
            "A".repeat(19),
            "A".repeat(21) + " B",
            "A".repeat(257),
        ).forEach { candidate ->
            assertEquals(ConnectQrValidation.InvalidQr, ConnectQrContract.validate(candidate))
        }
    }

    @Test
    fun navigableStructuredAndIdentityLikePayloadsAreUnsupported() {
        listOf(
            "https://example.test/pair/value",
            "www.example.test/pair/value",
            "{\"pairingToken\":\"value\"}",
            "[\"value\"]",
            "person@example.test",
            "mailto:person@example.test",
        ).forEach { candidate ->
            assertEquals(ConnectQrValidation.UnsupportedQr, ConnectQrContract.validate(candidate))
        }
    }

    @Test
    fun acceptedAlphabetContainsOnlyOpaqueBase64UrlCharacters() {
        assertIs<ConnectQrValidation.Valid>(ConnectQrContract.validate("Ab_9-".repeat(8)))
        assertTrue(ConnectQrContract.validate("A".repeat(20)) is ConnectQrValidation.Valid)
    }
}
