package com.botglobal.nqrb.calling

import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.notifications.PushMessage
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class NqrbPushMessageHandlerTests {
    @Test
    fun incomingCallRevalidatesBeforeNotificationPresentation() = runTest {
        val session = RecordingPushSession()
        val handler = handler(session)
        val callId = validCallId()

        handler.onMessage(message("incoming_call", callId))

        assertEquals(listOf(CallId(callId)), session.received)
        assertEquals(emptyList(), session.dismissed)
    }

    @Test
    fun incomingCallRetriesColdConnectionTimeoutBeforePresenting() = runTest {
        val session = RecordingPushSession(failuresRemaining = 1)
        val pauses = mutableListOf<Long>()
        val handler = NqrbPushMessageHandler(
            session = session,
            nowEpochMillis = { 2_000 },
            pause = { pauses += it },
            logger = { _, _ -> },
        )
        val callId = validCallId()

        handler.onMessage(message("incoming_call", callId))

        assertEquals(2, session.attempts)
        assertEquals(listOf(500L), pauses)
        assertEquals(listOf(CallId(callId)), session.received)
    }

    @Test
    fun incomingCallStopsRetryingWhenOfferExpires() = runTest {
        val session = RecordingPushSession(failuresRemaining = 3)
        var now = 1_500L
        val handler = NqrbPushMessageHandler(
            session = session,
            nowEpochMillis = { now },
            pause = { now += it },
            logger = { _, _ -> },
        )
        val callId = validCallId()

        handler.onMessage(message("incoming_call", callId, sentAt = 1_000, ttl = 1))

        assertEquals(1, session.attempts)
        assertEquals(emptyList(), session.received)
        assertEquals(listOf(CallId(callId) to CallTerminationReason.Expired), session.dismissed)
    }

    @Test
    fun expiredIncomingPushDoesNotRevalidateOrPresent() = runTest {
        val session = RecordingPushSession()
        val handler = handler(session, nowEpochMillis = 40_000)
        val callId = validCallId()

        handler.onMessage(message("incoming_call", callId, sentAt = 1_000, ttl = 30))

        assertEquals(emptyList(), session.received)
        assertEquals(listOf(CallId(callId) to CallTerminationReason.Expired), session.dismissed)
    }

    @Test
    fun serverRingExpiryRejectsDelayedPushEvenWhenFirebaseTtlIsLong() = runTest {
        val session = RecordingPushSession()
        val handler = handler(session, nowEpochMillis = 40_000)
        val callId = validCallId()

        handler.onMessage(message("incoming_call", callId, ttl = 86_400,
            expiresAtUtc = "1970-01-01T00:00:30Z"))

        assertEquals(emptyList(), session.received)
        assertEquals(listOf(CallId(callId) to CallTerminationReason.Expired), session.dismissed)
    }

    @Test
    fun cancellationPushClearsCurrentIncomingPresentation() = runTest {
        val session = RecordingPushSession()
        val handler = handler(session)
        val callId = validCallId()

        handler.onMessage(message("incoming_call_cancelled", callId))

        assertEquals(emptyList(), session.received)
        assertEquals(listOf(CallId(callId) to CallTerminationReason.Cancelled), session.dismissed)
    }

    @Test
    fun answeredElsewherePushClearsCurrentIncomingPresentation() = runTest {
        val session = RecordingPushSession()
        val handler = handler(session)
        val callId = validCallId()

        handler.onMessage(message("incoming_call_answered_elsewhere", callId))

        assertEquals(listOf(CallId(callId) to CallTerminationReason.Cancelled), session.dismissed)
    }

    @Test
    fun invalidCallIdIsIgnored() = runTest {
        val session = RecordingPushSession()
        val notifications = RecordingGeneralNotifications()

        handler(session, notifications = notifications)
            .onMessage(message("incoming_call", "not-a-call-id"))

        assertEquals(emptyList(), session.received)
        assertEquals(emptyList(), session.dismissed)
        assertEquals(emptyList(), notifications.shown)
    }

    @Test
    fun generalCampaignPushShowsNotification() = runTest {
        val session = RecordingPushSession()
        val notifications = RecordingGeneralNotifications()

        handler(session, notifications = notifications).onMessage(
            PushMessage(
                messageId = "campaign-1",
                data = mapOf(
                    "title" to "Welcome to Nqrb",
                    "body" to "Thanks for trying Nqrb.",
                ),
                sentAtEpochMilliseconds = 1_000,
                timeToLiveSeconds = 30,
            ),
        )

        assertEquals(emptyList(), session.received)
        assertEquals(emptyList(), session.dismissed)
        assertEquals(
            listOf(ShownNotification("campaign-1", "Welcome to Nqrb", "Thanks for trying Nqrb.")),
            notifications.shown,
        )
    }

    @Test
    fun incomingCallPushDoesNotShowGeneralNotification() = runTest {
        val session = RecordingPushSession()
        val notifications = RecordingGeneralNotifications()
        val callId = validCallId()

        handler(session, notifications = notifications).onMessage(
            PushMessage(
                messageId = "call-1",
                data = mapOf(
                    "type" to "incoming_call",
                    "callId" to callId,
                    "title" to "Incoming call",
                    "body" to "Someone is calling",
                ),
                sentAtEpochMilliseconds = 1_000,
                timeToLiveSeconds = 30,
            ),
        )

        assertEquals(listOf(CallId(callId)), session.received)
        assertEquals(emptyList(), notifications.shown)
    }

    private fun handler(
        session: RecordingPushSession,
        nowEpochMillis: Long = 2_000,
        notifications: NqrbGeneralPushNotificationSink = RecordingGeneralNotifications(),
    ) = NqrbPushMessageHandler(
        session = session,
        generalNotifications = notifications,
        nowEpochMillis = { nowEpochMillis },
        logger = { _, _ -> },
    )

    private fun message(
        type: String,
        callId: String,
        sentAt: Long = 1_000,
        ttl: Int = 30,
        expiresAtUtc: String? = null,
    ) = PushMessage(
        messageId = "message-$type",
        data = mapOf("type" to type, "callId" to callId) +
            (expiresAtUtc?.let { mapOf("expiresAtUtc" to it) } ?: emptyMap()),
        sentAtEpochMilliseconds = sentAt,
        timeToLiveSeconds = ttl,
    )

    private fun validCallId() = "11111111-1111-4111-8111-111111111111"

    private class RecordingPushSession(private var failuresRemaining: Int = 0) : NqrbIncomingCallPushSession {
        val received = mutableListOf<CallId>()
        val dismissed = mutableListOf<Pair<CallId, CallTerminationReason>>()
        var attempts = 0

        override suspend fun receiveIncoming(callId: CallId) {
            attempts++
            if (failuresRemaining-- > 0) throw TimeoutException("cold connection")
            received += callId
        }

        override suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason) {
            dismissed += callId to reason
        }
    }

    private data class ShownNotification(
        val messageId: String?,
        val title: String,
        val body: String,
    )

    private class RecordingGeneralNotifications : NqrbGeneralPushNotificationSink {
        val shown = mutableListOf<ShownNotification>()

        override suspend fun show(messageId: String?, title: String, body: String) {
            shown += ShownNotification(messageId, title, body)
        }
    }
}
