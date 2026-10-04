package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.reviews.ReviewPolicy
import com.botglobal.mobile.platform.reviews.ReviewTrigger

const val NqrbMeaningfulCallDurationSeconds = 60L

private const val DayMillis = 24L * 60L * 60L * 1_000L

val NqrbPlayReviewPolicy = ReviewPolicy(
    firstUseAgeMillis = 2L * DayMillis,
    minimumMeaningfulEvents = 2,
    minimumMeaningfulEventSpanMillis = DayMillis,
    cooldownAfterAttemptMillis = 120L * DayMillis,
    triggers = setOf(ReviewTrigger.CompletedExperience),
)
