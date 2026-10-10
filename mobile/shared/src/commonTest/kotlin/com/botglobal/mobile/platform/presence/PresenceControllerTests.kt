package com.botglobal.mobile.platform.presence

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceControllerTests {
    @Test
    fun disabled_gateway_performs_zero_io_and_stays_disabled() = runTest {
        val gateway = FakeGateway(enabled = false)
        val controller = PresenceController(backgroundScope, gateway)

        controller.bind(account("first"))
        controller.suspend()
        controller.resume()
        controller.clear()

        assertEquals(PresenceLifecycleState.Disabled, controller.state.value.state)
        assertEquals(0, gateway.activations)
        assertEquals(0, gateway.deactivations)
    }

    @Test
    fun account_switch_invalidates_the_prior_lease_and_fences_late_events() = runTest {
        val gateway = FakeGateway()
        val controller = PresenceController(backgroundScope, gateway)
        runCurrent()
        controller.bind(account("first"))
        val firstGeneration = controller.state.value.generation
        controller.bind(account("second"))
        val secondGeneration = controller.state.value.generation

        gateway.mutableEvents.emit(PresenceGatewayEvent.Connected(firstGeneration, 10))
        runCurrent()
        assertEquals(PresenceLifecycleState.Connecting, controller.state.value.state)

        gateway.mutableEvents.emit(PresenceGatewayEvent.Connected(secondGeneration, 20))
        runCurrent()

        assertEquals(PresenceLifecycleState.Connected, controller.state.value.state)
        assertEquals(20, controller.state.value.lastObservedAtEpochMillis)
        assertEquals(listOf("lease-first"), gateway.invalidated)
    }

    @Test
    fun background_is_explicitly_suspended_and_never_published_as_disconnected() = runTest {
        val gateway = FakeGateway()
        val controller = PresenceController(backgroundScope, gateway)
        controller.bind(account("first"))

        controller.suspend()
        gateway.mutableEvents.emit(PresenceGatewayEvent.Unknown(controller.state.value.generation, "background_ambiguous"))
        runCurrent()

        assertEquals(PresenceLifecycleState.Suspended, controller.state.value.state)
        assertEquals(1, gateway.suspensions)
    }

    @Test
    fun clear_advances_before_a_stalled_activation_and_cleans_up_the_late_lease() = runTest {
        val gateway = FakeGateway().apply { activationGate = CompletableDeferred() }
        val controller = PresenceController(backgroundScope, gateway)
        val binding = launch { controller.bind(account("first")) }
        runCurrent()

        controller.clear()
        assertEquals(PresenceLifecycleState.Idle, controller.state.value.state)

        gateway.activationGate!!.complete(Unit)
        binding.join()

        assertEquals(PresenceLifecycleState.Idle, controller.state.value.state)
        assertEquals(listOf("lease-first"), gateway.invalidated)
    }

    @Test
    fun logout_invalidates_the_server_lease_and_returns_to_idle() = runTest {
        val gateway = FakeGateway()
        val controller = PresenceController(backgroundScope, gateway)
        controller.bind(account("first"))

        controller.clear()

        assertEquals(PresenceLifecycleState.Idle, controller.state.value.state)
        assertEquals(listOf("lease-first"), gateway.invalidated)
    }

    private fun account(member: String) = PresenceAccount("nqrb", member, "subject-$member")

    private class FakeGateway(override val enabled: Boolean = true) : PresenceGateway {
        val mutableEvents = MutableSharedFlow<PresenceGatewayEvent>(extraBufferCapacity = 8)
        override val events = mutableEvents
        var activations = 0
        var deactivations = 0
        var suspensions = 0
        var activationGate: CompletableDeferred<Unit>? = null
        val invalidated = mutableListOf<String>()

        override suspend fun activate(account: PresenceAccount, generation: Long): PresenceLease {
            activations++
            activationGate?.await()
            return PresenceLease(
                "lease-${account.membershipId}", "token", "https://presence.example/",
                "presenceConnections/uid/lease/connection", 100_000, 20, 40,
            )
        }

        override suspend fun suspend(lease: PresenceLease, generation: Long) { suspensions++ }
        override suspend fun resume(lease: PresenceLease, generation: Long) = lease
        override suspend fun deactivate(lease: PresenceLease, generation: Long, invalidate: Boolean) {
            deactivations++
            if (invalidate) invalidated += lease.leaseId
        }
    }
}
