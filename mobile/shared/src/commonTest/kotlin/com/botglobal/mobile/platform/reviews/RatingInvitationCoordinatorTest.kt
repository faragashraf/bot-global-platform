package com.botglobal.mobile.platform.reviews

import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatingInvitationCoordinatorTest {
    @Test
    fun repeated_event_does_not_qualify_and_later_defers_the_card() {
        val preferences = InMemoryPreferenceStore()
        var now = 0L
        val invitation = RatingInvitationCoordinator(
            preferences,
            "rating",
            RatingInvitationPolicy(minimumEvents = 2, minimumEventSpanMillis = 1_000, firstEventAgeMillis = 1_000),
            { now },
        )

        invitation.recordMeaningfulEvent("round-1")
        now = 1_000
        invitation.recordMeaningfulEvent("round-1")
        assertFalse(invitation.visible.value)
        invitation.recordMeaningfulEvent("round-2")
        assertTrue(invitation.visible.value)

        invitation.later()
        assertFalse(invitation.visible.value)
        assertTrue(invitation.suppressesNativePrompt())
        now += 29L * 24 * 60 * 60 * 1_000
        invitation.refresh()
        assertFalse(invitation.visible.value)
        now += 24L * 60 * 60 * 1_000
        invitation.refresh()
        assertTrue(invitation.visible.value)
    }

    @Test
    fun opening_store_suppresses_repeated_invitation_without_claiming_a_review() {
        val preferences = InMemoryPreferenceStore()
        var now = 0L
        val invitation = RatingInvitationCoordinator(
            preferences,
            "rating",
            RatingInvitationPolicy(minimumEvents = 1, minimumEventSpanMillis = 0, firstEventAgeMillis = 0),
            { now },
        )

        invitation.recordMeaningfulEvent("paired")
        assertTrue(invitation.visible.value)
        invitation.openedStore()
        assertFalse(invitation.visible.value)
        assertTrue(invitation.suppressesNativePrompt())

        val restored = RatingInvitationCoordinator(
            preferences,
            "rating",
            RatingInvitationPolicy(minimumEvents = 1, minimumEventSpanMillis = 0, firstEventAgeMillis = 0),
            { now },
        )
        restored.refresh()
        assertFalse(restored.visible.value)
        now += 180L * 24 * 60 * 60 * 1_000
        restored.refresh()
        assertTrue(restored.visible.value)
    }
}
