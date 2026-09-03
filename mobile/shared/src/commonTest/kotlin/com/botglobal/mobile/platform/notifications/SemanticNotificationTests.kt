package com.botglobal.mobile.platform.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SemanticNotificationTests {
    private val parser = SemanticPushEnvelopeParser(
        externalLinks = HttpsHostAllowlist(setOf("notify.example.com")),
        internalDestination = { route ->
            route.takeIf { it == "notifications" }
                ?.let(SemanticNotificationDestination::Internal)
        },
    )

    @Test
    fun semanticEnvelopeParsesValidatedFieldsAndPriority() {
        val notification = parser.parse(message(
            "notificationId" to VALID_ID,
            "titleAr" to "Arabic title",
            "titleEn" to "English title",
            "bodyAr" to "Arabic body",
            "bodyEn" to "English body",
            "type" to "security",
            "priority" to "high",
            "destination" to "notifications",
            "createdAtUtc" to "2026-09-03T10:00:00Z",
            "sound" to "soft",
        ))!!

        assertEquals(VALID_ID, notification.id)
        assertEquals(SemanticNotificationPriority.High, notification.priority)
        assertEquals(SemanticNotificationDestination.Internal("notifications"), notification.destination)
        assertEquals("soft", notification.soundKey)
    }

    @Test
    fun invalidEnvelopeIsRejected() {
        assertNull(parser.parse(message("notificationId" to "../settings")))
        assertNull(parser.parse(message("titleEn" to "missing identity")))
    }

    @Test
    fun providerSentTimeSuppliesMissingEnvelopeTimestamp() {
        val notification = parser.parse(
            PushMessage(
                messageId = "provider-id",
                data = mapOf("notificationId" to VALID_ID),
                sentAtEpochMilliseconds = 1_725_192_000_000,
                timeToLiveSeconds = 60,
            ),
        )!!

        assertEquals("2024-09-01T12:00:00Z", notification.createdAtUtc)
    }

    @Test
    fun duplicateMessageIsPersistedAndPresentedOnce() = runTest {
        val inbox = InMemoryNotificationInbox()
        var presentations = 0
        val handler = SemanticPushMessageHandler(parser, inbox) {
            presentations++
            NotificationPresentationOutcome.Presented
        }
        val message = message("notificationId" to VALID_ID)

        handler.onMessage(message)
        handler.onMessage(message)

        assertEquals(1, inbox.list().size)
        assertEquals(1, presentations)
    }

    @Test
    fun priorityMappingPreservesLegacyHighAndDefaultsSafely() {
        assertEquals(SemanticNotificationPriority.High, semanticNotificationPriority("2"))
        assertEquals(SemanticNotificationPriority.High, semanticNotificationPriority("HIGH"))
        assertEquals(SemanticNotificationPriority.Normal, semanticNotificationPriority("urgent"))
        assertEquals(SemanticNotificationPriority.Normal, semanticNotificationPriority(null))
    }

    @Test
    fun destinationValidationAcceptsOnlyAllowlistedHttps() {
        val accepted = parser.parse(message(
            "notificationId" to VALID_ID,
            "actionUrl" to "https://notify.example.com/action?id=1",
        ))!!
        val rejected = parser.parse(message(
            "notificationId" to OTHER_ID,
            "actionUrl" to "https://notify.example.com.evil.invalid/action",
        ))!!

        assertIs<SemanticNotificationDestination.ExternalHttps>(accepted.destination)
        assertNull(rejected.destination)
        assertNull(HttpsHostAllowlist(setOf("notify.example.com")).validated("http://notify.example.com"))
        assertNull(HttpsHostAllowlist(setOf("notify.example.com")).validated("https://user@notify.example.com"))
    }

    @Test
    fun processDeadHandlerRequiresNoUiState() = runTest {
        val inbox = InMemoryNotificationInbox()
        val handler: PushMessageHandler = SemanticPushMessageHandler(parser, inbox)

        handler.onMessage(message("notificationId" to VALID_ID))

        assertEquals(VALID_ID, inbox.list().single().id)
    }

    @Test
    fun permissionDenialCannotPreventInboxPersistence() = runTest {
        val inbox = InMemoryNotificationInbox()
        val handler = SemanticPushMessageHandler(parser, inbox) {
            NotificationPresentationOutcome.PermissionDenied
        }

        handler.onMessage(message("notificationId" to VALID_ID))

        assertTrue(inbox.list().single().id == VALID_ID)
    }

    @Test
    fun defaultDiagnosticsRedactProviderIdentityAndNotificationContent() {
        val message = message(
            "notificationId" to VALID_ID,
            "body" to "private body",
        )
        val notification = parser.parse(message)!!

        assertTrue("private body" !in message.toString())
        assertTrue("provider-id" !in message.toString())
        assertTrue("private body" !in notification.toString())
    }

    private fun message(vararg values: Pair<String, String>) = PushMessage(
        messageId = "provider-id",
        data = mapOf(*values),
        sentAtEpochMilliseconds = 1L,
        timeToLiveSeconds = 60,
    )

    private companion object {
        const val VALID_ID = "01234567-89ab-cdef-0123-456789abcdef"
        const val OTHER_ID = "fedcba98-7654-3210-fedc-ba9876543210"
    }
}
