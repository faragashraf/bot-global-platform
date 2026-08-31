package com.botglobal.mobile.platform.notifications.firebase

import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushRegistration
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class FirebaseRegistrationCoordinatorTests {
    @Test
    fun acquisitionAndRefreshArePersistedAndHandedOff() = runTest {
        val registration = RecordingRegistration()
        val store = MemoryStore()
        val client = RecordingClient()
        val coordinator = FirebaseRegistrationCoordinator(
            PushRegistrationController(registration),
            store,
            client,
        )
        coordinator.activate()

        coordinator.onRegistered("first-fid")
        coordinator.onRegistered("second-fid")

        assertEquals(listOf("first-fid", "second-fid"), registration.identifiers)
        assertEquals("second-fid", store.value)
        assertEquals(1, client.registerCalls)
    }

    @Test
    fun storedIdentifierIsRetriedAfterProcessRecreation() = runTest {
        val registration = RecordingRegistration()
        val store = MemoryStore("persisted-fid")
        val coordinator = FirebaseRegistrationCoordinator(
            PushRegistrationController(registration),
            store,
            RecordingClient(),
        )

        coordinator.activate()

        assertEquals(listOf("persisted-fid"), registration.identifiers)
    }

    @Test
    fun deactivationUnregistersBackendAndFirebaseAndClearsLocalIdentifier() = runTest {
        val registration = RecordingRegistration()
        val store = MemoryStore("persisted-fid")
        val client = RecordingClient()
        val coordinator = FirebaseRegistrationCoordinator(
            PushRegistrationController(registration),
            store,
            client,
        )
        coordinator.activate()

        coordinator.deactivate()

        assertEquals(1, registration.unregisterCalls)
        assertEquals(1, client.unregisterCalls)
        assertNull(store.value)
    }

    private class MemoryStore(var value: String? = null) : FirebaseDestinationStore {
        override fun read() = value
        override fun write(identifier: String) { value = identifier }
        override fun clear() { value = null }
    }

    private class RecordingClient : FirebaseRegistrationClient {
        var registerCalls = 0
        var unregisterCalls = 0
        override suspend fun register() { registerCalls++ }
        override suspend fun unregister() { unregisterCalls++ }
    }

    private class RecordingRegistration : PushRegistration {
        val identifiers = mutableListOf<String>()
        var unregisterCalls = 0
        override suspend fun register(destination: PushDestination): PushRegistrationOutcome {
            identifiers += destination.identifier.value
            return PushRegistrationOutcome.Registered
        }

        override suspend fun unregister(): PushRegistrationOutcome {
            unregisterCalls++
            return PushRegistrationOutcome.Unregistered
        }
    }
}
