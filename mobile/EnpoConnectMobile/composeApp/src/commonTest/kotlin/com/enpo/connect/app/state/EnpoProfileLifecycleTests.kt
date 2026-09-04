package com.enpo.connect.app.state

import com.botglobal.mobile.platform.profile.ProfileController
import com.botglobal.mobile.platform.profile.ProfileFetchResult
import com.botglobal.mobile.platform.profile.ProfileLoadState
import com.botglobal.mobile.platform.profile.ProfileSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EnpoProfileLifecycleTests {
    @Test
    fun pairedBootstrapLoadsProfileFromConfiguredBotGlobalRepository() = runTest {
        var fetches = 0
        val snapshot = ProfileSnapshot(
            displayName = "Synthetic User",
            jobTitle = "Specialist",
            organizationUnit = "Operations",
            version = 2,
            updatedAtUtc = "2026-09-04T08:00:00Z",
        )
        val controller = ProfileController {
            fetches += 1
            ProfileFetchResult.Available(snapshot)
        }

        synchronizeEnpoProfile(
            EnpoBootstrapState.DeviceCredentialAvailable,
            controller,
        )

        assertEquals(1, fetches)
        assertEquals(ProfileLoadState.Ready(snapshot), controller.state.value)
    }

    @Test
    fun unpairedOrUnreadableStateInvalidatesProfile() = runTest {
        val controller = ProfileController {
            ProfileFetchResult.Available(
                ProfileSnapshot(
                    "Synthetic User",
                    null,
                    null,
                    1,
                    "2026-09-04T08:00:00Z",
                ),
            )
        }
        controller.refresh()

        synchronizeEnpoProfile(EnpoBootstrapState.Unpaired, controller)

        assertEquals(ProfileLoadState.NotLoaded, controller.state.value)
    }
}
