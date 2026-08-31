package com.botglobal.mobile.platform.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PushRegistrationControllerTests {
    @Test
    fun destinationAcquiredBeforeAuthenticationIsHandedOffOnActivation() = runTest {
        val registration = RecordingRegistration()
        val controller = PushRegistrationController(registration)

        controller.destinationAvailable(destination("first"))
        assertEquals(0, registration.registerCalls)

        controller.activate()

        assertEquals(1, registration.registerCalls)
        assertEquals("first", registration.destinations.single().identifier.value)
    }

    @Test
    fun refreshedDestinationRegistersOnceAndExactDuplicatesAreIgnored() = runTest {
        val registration = RecordingRegistration()
        val controller = PushRegistrationController(registration)
        controller.activate()

        controller.destinationAvailable(destination("first"))
        controller.destinationAvailable(destination("first"))
        controller.destinationAvailable(destination("second"))
        controller.destinationAvailable(destination("second"))

        assertEquals(listOf("first", "second"), registration.destinations.map { it.identifier.value })
    }

    @Test
    fun failedRegistrationRemainsRetryable() = runTest {
        val registration = RecordingRegistration(
            outcomes = ArrayDeque(
                listOf(
                    PushRegistrationOutcome.RetryableFailure,
                    PushRegistrationOutcome.Registered,
                ),
            ),
        )
        val controller = PushRegistrationController(registration)
        controller.activate()

        controller.destinationAvailable(destination("retry"))
        controller.destinationAvailable(destination("retry"))

        assertEquals(2, registration.registerCalls)
    }

    @Test
    fun deactivationInvalidatesRegistrationOnlyOnce() = runTest {
        val registration = RecordingRegistration()
        val controller = PushRegistrationController(registration)
        controller.activate()
        controller.destinationAvailable(destination("first"))

        controller.deactivate()
        controller.deactivate()

        assertEquals(1, registration.unregisterCalls)
    }

    @Test
    fun destinationValueIsRedactedFromDefaultDiagnostics() {
        val destination = destination("private-fid")

        assertEquals("<redacted>", destination.identifier.toString())
        check(!destination.toString().contains("private-fid"))
    }

    private fun destination(value: String) = PushDestination(
        provider = "fcm",
        identifier = OpaquePushDestinationId(value),
    )

    private class RecordingRegistration(
        private val outcomes: ArrayDeque<PushRegistrationOutcome> = ArrayDeque(),
    ) : PushRegistration {
        val destinations = mutableListOf<PushDestination>()
        var unregisterCalls = 0
        val registerCalls: Int get() = destinations.size

        override suspend fun register(destination: PushDestination): PushRegistrationOutcome {
            destinations += destination
            return outcomes.removeFirstOrNull() ?: PushRegistrationOutcome.Registered
        }

        override suspend fun unregister(): PushRegistrationOutcome {
            unregisterCalls++
            return PushRegistrationOutcome.Unregistered
        }
    }
}
