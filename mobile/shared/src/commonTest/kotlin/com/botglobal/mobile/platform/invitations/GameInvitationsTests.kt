package com.botglobal.mobile.platform.invitations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameInvitationsTests {
    private val codec = InvitationLinkCodec("familygames://invite")
    private val token = "AbCdEf0123456789_opaque-token"

    @Test
    fun deep_link_round_trip_preserves_opaque_invitation_identity() {
        val result = codec.parse(codec.encode(token))

        assertEquals(token, assertIs<InvitationLinkResult.Valid>(result).token)
    }

    @Test
    fun parser_rejects_wrong_destination_and_malformed_token() {
        assertIs<InvitationLinkResult.Invalid>(codec.parse("familygames://sessions/$token"))
        assertIs<InvitationLinkResult.Invalid>(codec.parse("familygames://invite/short"))
        assertIs<InvitationLinkResult.Invalid>(codec.parse("https://attacker.invalid/invite/$token"))
    }

    @Test
    fun arabic_and_english_messages_resolve_to_identical_invitation_identity() {
        val link = codec.encode(token)
        val arabic = InvitationShareFormatter.format(
            InvitationMessageLanguage.Arabic,
            "إكس أو",
            link,
            "AX7K2Q",
        )
        val english = InvitationShareFormatter.format(
            InvitationMessageLanguage.English,
            "Tic-Tac-Toe",
            link,
            "AX7K2Q",
        )

        val arabicToken = assertIs<InvitationLinkResult.Valid>(codec.parse(linkFrom(arabic.message))).token
        val englishToken = assertIs<InvitationLinkResult.Valid>(codec.parse(linkFrom(english.message))).token
        assertEquals(token, arabicToken)
        assertEquals(arabicToken, englishToken)
        assertTrue(arabic.message.contains("AX7K2Q"))
        assertTrue(english.message.contains("AX7K2Q"))
    }

    @Test
    fun generated_qr_and_share_payloads_contain_no_session_credentials() {
        val link = codec.encode(token)
        val share = InvitationShareFormatter.format(
            InvitationMessageLanguage.English,
            "Tic-Tac-Toe",
            link,
            "AX7K2Q",
        )
        val forbidden = listOf(
            "access-secret-value",
            "refresh-secret-value",
            "password-value",
            "private-user@example.test",
            "server-secret-value",
        )

        forbidden.forEach { sensitive ->
            assertFalse(link.contains(sensitive))
            assertFalse(share.message.contains(sensitive))
        }
        assertTrue(link.endsWith(token))
    }

    private fun linkFrom(message: String): String =
        message.lineSequence().first { it.startsWith("familygames://") }
}
