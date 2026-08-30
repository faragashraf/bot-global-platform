package com.botglobal.mobile.platform.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkAvailabilityTests {
    @Test
    fun unavailable_requires_a_short_stable_confirmation() = runTest {
        val raw = MutableSharedFlow<NetworkAvailabilityState>(extraBufferCapacity = 4)
        val snapshots = mutableListOf<NetworkAvailabilitySnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stabilizedNetworkAvailability(raw, observerGeneration = 7, unavailableConfirmationMillis = 750)
                .toList(snapshots)
        }

        raw.emit(NetworkAvailabilityState.Available)
        runCurrent()
        raw.emit(NetworkAvailabilityState.Unavailable)
        advanceTimeBy(749)
        runCurrent()

        assertEquals(listOf(NetworkAvailabilityState.Available), snapshots.map { it.state })

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf(NetworkAvailabilityState.Available, NetworkAvailabilityState.Unavailable),
            snapshots.map { it.state },
        )
        assertEquals(listOf(1L, 2L), snapshots.map { it.revision })
        assertEquals(listOf(7L, 7L), snapshots.map { it.observerGeneration })
    }

    @Test
    fun transient_unavailable_noise_does_not_publish_a_false_loss() = runTest {
        val raw = MutableSharedFlow<NetworkAvailabilityState>(extraBufferCapacity = 4)
        val snapshots = mutableListOf<NetworkAvailabilitySnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stabilizedNetworkAvailability(raw, observerGeneration = 3, unavailableConfirmationMillis = 750)
                .toList(snapshots)
        }

        raw.emit(NetworkAvailabilityState.Available)
        runCurrent()
        raw.emit(NetworkAvailabilityState.Unavailable)
        advanceTimeBy(200)
        raw.emit(NetworkAvailabilityState.Available)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(NetworkAvailabilityState.Available), snapshots.map { it.state })
        assertEquals(listOf(1L), snapshots.map { it.revision })
    }
}
