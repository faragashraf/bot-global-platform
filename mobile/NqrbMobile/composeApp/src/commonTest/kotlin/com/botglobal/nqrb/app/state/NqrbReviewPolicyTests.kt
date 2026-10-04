package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.reviews.ReviewTrigger
import kotlin.test.Test
import kotlin.test.assertEquals

class NqrbReviewPolicyTests {
    @Test
    fun review_is_requested_only_after_two_meaningful_calls_on_different_days() {
        val dayMillis = 24L * 60L * 60L * 1_000L

        assertEquals(60L, NqrbMeaningfulCallDurationSeconds)
        assertEquals(2L * dayMillis, NqrbPlayReviewPolicy.firstUseAgeMillis)
        assertEquals(2, NqrbPlayReviewPolicy.minimumMeaningfulEvents)
        assertEquals(dayMillis, NqrbPlayReviewPolicy.minimumMeaningfulEventSpanMillis)
        assertEquals(120L * dayMillis, NqrbPlayReviewPolicy.cooldownAfterAttemptMillis)
        assertEquals(setOf(ReviewTrigger.CompletedExperience), NqrbPlayReviewPolicy.triggers)
    }
}
