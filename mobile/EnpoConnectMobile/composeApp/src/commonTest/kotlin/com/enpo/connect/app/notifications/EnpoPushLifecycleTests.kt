package com.enpo.connect.app.notifications

import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.InMemoryNotificationInbox
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.NotificationPresentationOutcome
import com.botglobal.mobile.platform.notifications.OpaquePushDestinationId
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushRegistration
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import com.botglobal.mobile.platform.notifications.SemanticPushMessageHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class EnpoPushLifecycleTests {
    @Test
    fun pairedActivationRegistersAvailableFcmDestination() = runTest {
        val registration = RecordingRegistration()
        val controller = PushRegistrationController(registration)
        controller.destinationAvailable(destination("first"))

        controller.activate()

        assertEquals(listOf("first"), registration.identifiers)
    }

    @Test
    fun tokenRefreshUpdatesOnceAndExactDuplicateIsIdempotent() = runTest {
        val registration = RecordingRegistration()
        val controller = PushRegistrationController(registration)
        controller.activate()

        controller.destinationAvailable(destination("first"))
        controller.destinationAvailable(destination("second"))
        controller.destinationAvailable(destination("second"))

        assertEquals(listOf("first", "second"), registration.identifiers)
    }

    @Test
    fun registrationFailureCannotClearPairedCredential() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(
            MobileDeviceCredential("device", "credential"),
        )
        val controller = PushRegistrationController(
            RecordingRegistration(PushRegistrationOutcome.RetryableFailure),
        )
        controller.activate()

        controller.destinationAvailable(destination("temporary"))

        assertNotNull(vault.restore())
    }

    @Test
    fun backgroundAndProcessDeadPathPersistsWithoutComposeBootstrap() = runTest {
        val inbox = InMemoryNotificationInbox()
        val handler = SemanticPushMessageHandler(EnpoNotificationContract.parser(), inbox)

        handler.onMessage(message())

        assertEquals(1, inbox.list().size)
    }

    @Test
    fun permissionDeniedPresentationStillPersistsAndDeduplicates() = runTest {
        val inbox = InMemoryNotificationInbox()
        val handler = SemanticPushMessageHandler(EnpoNotificationContract.parser(), inbox) {
            NotificationPresentationOutcome.PermissionDenied
        }

        handler.onMessage(message())
        handler.onMessage(message())

        assertEquals(1, inbox.list().size)
    }

    private fun message() = PushMessage(
        messageId = "provider-message",
        data = mapOf(
            "notificationId" to "01234567-89ab-cdef-0123-456789abcdef",
            "titleAr" to "title",
            "bodyAr" to "body",
        ),
        sentAtEpochMilliseconds = 0,
        timeToLiveSeconds = 60,
    )

    private fun destination(value: String) =
        PushDestination("fcm", OpaquePushDestinationId(value))

    private class RecordingRegistration(
        private val outcome: PushRegistrationOutcome = PushRegistrationOutcome.Registered,
    ) : PushRegistration {
        val identifiers = mutableListOf<String>()

        override suspend fun register(destination: PushDestination): PushRegistrationOutcome {
            identifiers += destination.identifier.value
            return outcome
        }

        override suspend fun unregister(): PushRegistrationOutcome =
            PushRegistrationOutcome.Unregistered
    }
}
