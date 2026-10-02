package com.botglobal.mobile.platform.notifications.firebase

import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FirebasePushMessageDispatcherTests {
    @Test
    fun asyncDeliveryReturnsBeforeHandlerCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        var handled = false
        val dispatcher = FirebasePushMessageDispatcher(
            handler = PushMessageHandler {
                gate.await()
                handled = true
            },
            scope = backgroundScope,
            policy = FirebaseMessageDeliveryPolicy(),
        )

        dispatcher.dispatch(message())
        runCurrent()

        assertFalse(handled)
        gate.complete(Unit)
        runCurrent()
        assertTrue(handled)
    }

    @Test
    fun blockingDeliveryCompletesBeforeReturningToFirebaseCallback() {
        var handled = false
        val dispatcher = FirebasePushMessageDispatcher(
            handler = PushMessageHandler { handled = true },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            policy = FirebaseMessageDeliveryPolicy(blockOnMessage = true),
        )

        dispatcher.dispatch(message())

        assertTrue(handled)
    }

    private fun message() = PushMessage(
        messageId = "message",
        data = mapOf("type" to "incoming_call"),
        sentAtEpochMilliseconds = 1_000,
        timeToLiveSeconds = 30,
    )
}
