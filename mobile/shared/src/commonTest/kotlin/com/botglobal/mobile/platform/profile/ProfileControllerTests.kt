package com.botglobal.mobile.platform.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class ProfileControllerTests {
    @Test
    fun availableProfileBecomesReadyWithoutExposingContentInDiagnostics() = runTest {
        val snapshot = snapshot()
        val controller = ProfileController {
            ProfileFetchResult.Available(snapshot)
        }

        controller.refresh()

        assertEquals(ProfileLoadState.Ready(snapshot), controller.state.value)
        assertFalse(snapshot.toString().contains(snapshot.displayName))
    }

    @Test
    fun notAvailableAuthenticationAndFailureHaveDistinctStates() = runTest {
        val expectations = listOf(
            ProfileFetchResult.NotAvailableYet to ProfileLoadState.NotAvailableYet,
            ProfileFetchResult.AuthenticationRequired to ProfileLoadState.AuthenticationRequired,
            ProfileFetchResult.Failed to ProfileLoadState.Error,
        )

        expectations.forEach { (result, expected) ->
            val controller = ProfileController { result }
            controller.refresh()
            assertEquals(expected, controller.state.value)
        }
    }

    @Test
    fun invalidationRemovesInMemoryProfileState() = runTest {
        val controller = ProfileController {
            ProfileFetchResult.Available(snapshot())
        }
        controller.refresh()

        controller.invalidate()

        assertEquals(ProfileLoadState.NotLoaded, controller.state.value)
    }

    @Test
    fun unexpectedRepositoryFailureBecomesRetryableErrorState() = runTest {
        val controller = ProfileController {
            error("synthetic transport failure")
        }

        controller.refresh()

        assertEquals(ProfileLoadState.Error, controller.state.value)
    }

    private fun snapshot() = ProfileSnapshot(
        displayName = "Synthetic User",
        jobTitle = "Specialist",
        organizationUnit = "Operations",
        version = 4,
        updatedAtUtc = "2026-09-04T08:00:00Z",
    )
}
