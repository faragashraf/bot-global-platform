package com.botglobal.mobile.platform.reviews

import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ReviewCoordinatorTests {
    @Test
    fun same_event_is_not_counted_twice_after_recomposition_or_restart() = runTest {
        var now = 0L
        val store = InMemoryPreferenceStore()
        val launcher = CountingLauncher()
        val policy = ReviewPolicy(firstUseAgeMillis = 0, minimumMeaningfulEvents = 2, minimumMeaningfulEventSpanMillis = 10)
        val first = ReviewCoordinator(store, "reviews", policy, launcher) { now }
        first.recordMeaningfulEvent("round-1", 0)
        first.recordMeaningfulEvent("round-1", 1)

        val recreated = ReviewCoordinator(store, "reviews", policy, launcher) { now }
        now = 20
        recreated.recordMeaningfulEvent("round-2", 20)
        recreated.tryRequest(ReviewTrigger.CompletedExperience)

        assertEquals(1, launcher.launches)
    }

    @Test
    fun first_use_age_and_event_span_gate_native_launch() = runTest {
        var now = 0L
        val launcher = CountingLauncher()
        val reviews = ReviewCoordinator(
            InMemoryPreferenceStore(),
            "reviews",
            ReviewPolicy(firstUseAgeMillis = 100, minimumMeaningfulEvents = 3, minimumMeaningfulEventSpanMillis = 50),
            launcher,
        ) { now }
        reviews.recordMeaningfulEvent("a", 0)
        reviews.recordMeaningfulEvent("b", 10)
        reviews.recordMeaningfulEvent("c", 20)
        now = 99
        assertEquals(ReviewAttemptResult.NotEligible, reviews.tryRequest(ReviewTrigger.CompletedExperience))
        now = 101
        assertEquals(ReviewAttemptResult.NotEligible, reviews.tryRequest(ReviewTrigger.CompletedExperience))
        reviews.recordMeaningfulEvent("d", 60)
        assertEquals(ReviewAttemptResult.Launched, reviews.tryRequest(ReviewTrigger.CompletedExperience))
    }

    @Test
    fun attempted_native_launch_starts_cooldown() = runTest {
        var now = 0L
        val launcher = CountingLauncher()
        val reviews = eligibleCoordinator(launcher) { now }
        reviews.recordMeaningfulEvent("a", 0)
        reviews.tryRequest(ReviewTrigger.CompletedExperience)
        now = 89
        reviews.recordMeaningfulEvent("b", 89)
        assertEquals(ReviewAttemptResult.NotEligible, reviews.tryRequest(ReviewTrigger.Foreground))
        now = 91
        assertEquals(ReviewAttemptResult.Launched, reviews.tryRequest(ReviewTrigger.Foreground))
        assertEquals(2, launcher.launches)
    }

    @Test
    fun concurrent_launches_are_bounded() = runTest {
        val launcher = BlockingLauncher()
        val reviews = eligibleCoordinator(launcher) { 0L }
        reviews.recordMeaningfulEvent("a", 0)

        val first = launch { reviews.tryRequest(ReviewTrigger.CompletedExperience) }
        runCurrent()

        assertEquals(ReviewAttemptResult.AlreadyRunning, reviews.tryRequest(ReviewTrigger.CompletedExperience))
        launcher.finish()
        first.join()
    }

    @Test
    fun cancellation_before_prelaunch_consumes_no_cooldown_and_releases_launch_lock() = runTest {
        val store = InMemoryPreferenceStore()
        val launcher = CancellableBeforePrelaunchLauncher()
        val reviews = eligibleCoordinator(launcher, store) { 0L }
        reviews.recordMeaningfulEvent("a", 0)

        val first = launch { reviews.tryRequest(ReviewTrigger.CompletedExperience) }
        launcher.entered.await()
        assertEquals(ReviewAttemptResult.AlreadyRunning, reviews.tryRequest(ReviewTrigger.CompletedExperience))

        first.cancelAndJoin()
        assertTrue(first.isCancelled)

        val retryLauncher = CountingLauncher()
        val recreated = eligibleCoordinator(retryLauncher, store) { 1L }
        assertEquals(ReviewAttemptResult.Launched, recreated.tryRequest(ReviewTrigger.CompletedExperience))
        assertEquals(1, retryLauncher.launches)
    }

    @Test
    fun cancellation_after_prelaunch_retains_attempted_cooldown_and_releases_launch_lock() = runTest {
        val store = InMemoryPreferenceStore()
        val launcher = CancellableAfterPrelaunchLauncher()
        val reviews = eligibleCoordinator(launcher, store) { 0L }
        reviews.recordMeaningfulEvent("a", 0)

        val first = launch { reviews.tryRequest(ReviewTrigger.CompletedExperience) }
        launcher.prelaunchCompleted.await()
        first.cancelAndJoin()
        assertTrue(first.isCancelled)

        val retryLauncher = CountingLauncher()
        val recreated = eligibleCoordinator(retryLauncher, store) { 1L }
        assertEquals(ReviewAttemptResult.NotEligible, recreated.tryRequest(ReviewTrigger.CompletedExperience))
        assertEquals(0, retryLauncher.launches)
    }

    @Test
    fun missing_native_launch_does_not_start_cooldown() = runTest {
        var now = 0L
        val store = InMemoryPreferenceStore()
        val launcher = DeferredLauncher()
        val reviews = ReviewCoordinator(
            store,
            "reviews",
            ReviewPolicy(
                firstUseAgeMillis = 0,
                minimumMeaningfulEvents = 1,
                minimumMeaningfulEventSpanMillis = 0,
                cooldownAfterAttemptMillis = 90,
                triggers = setOf(ReviewTrigger.CompletedExperience, ReviewTrigger.Foreground),
            ),
            launcher,
        ) { now }
        reviews.recordMeaningfulEvent("a", 0)

        assertEquals(ReviewAttemptResult.Deferred, reviews.tryRequest(ReviewTrigger.CompletedExperience))

        now = 1
        val realLauncher = CountingLauncher()
        val recreated = ReviewCoordinator(
            store,
            "reviews",
            ReviewPolicy(
                firstUseAgeMillis = 0,
                minimumMeaningfulEvents = 1,
                minimumMeaningfulEventSpanMillis = 0,
                cooldownAfterAttemptMillis = 90,
                triggers = setOf(ReviewTrigger.CompletedExperience, ReviewTrigger.Foreground),
            ),
            realLauncher,
        ) { now }

        assertEquals(ReviewAttemptResult.Launched, recreated.tryRequest(ReviewTrigger.CompletedExperience))
        assertEquals(1, realLauncher.launches)
    }

    @Test
    fun readiness_loss_after_native_info_defers_without_cooldown() = runTest {
        var ready = false
        val store = InMemoryPreferenceStore()
        val launcher = CountingLauncher()
        val reviews = eligibleCoordinator(launcher, store) { 0L }
        reviews.recordMeaningfulEvent("a", 0)

        assertEquals(
            ReviewAttemptResult.Deferred,
            reviews.tryRequest(ReviewTrigger.CompletedExperience) { ready },
        )
        assertEquals(0, launcher.launches)

        ready = true
        val recreated = eligibleCoordinator(launcher, store) { 1L }
        assertEquals(
            ReviewAttemptResult.Launched,
            recreated.tryRequest(ReviewTrigger.CompletedExperience) { ready },
        )
        assertEquals(1, launcher.launches)
    }

    @Test
    fun cooldown_persistence_failure_fails_closed_before_native_launch() = runTest {
        val launcher = CountingLauncher()
        val reviews = ReviewCoordinator(
            ThrowingPreferenceStore(reads = mapOf("reviews" to eligibleStateJson())),
            "reviews",
            ReviewPolicy(firstUseAgeMillis = 0, minimumMeaningfulEvents = 1, minimumMeaningfulEventSpanMillis = 0),
            launcher,
        ) { 0L }

        assertEquals(ReviewAttemptResult.Failed, reviews.tryRequest(ReviewTrigger.CompletedExperience))
        assertEquals(0, launcher.launches)
    }

    private fun eligibleCoordinator(
        launcher: ReviewPromptLauncher,
        store: PreferenceStore = InMemoryPreferenceStore(),
        now: () -> Long,
    ) = ReviewCoordinator(
        store,
        "reviews",
        ReviewPolicy(
            firstUseAgeMillis = 0,
            minimumMeaningfulEvents = 1,
            minimumMeaningfulEventSpanMillis = 0,
            cooldownAfterAttemptMillis = 90,
            triggers = setOf(ReviewTrigger.CompletedExperience, ReviewTrigger.Foreground),
        ),
        launcher,
        now,
    )

    private class CountingLauncher : ReviewPromptLauncher {
        var launches = 0
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            return when (beforeNativeLaunch()) {
                ReviewPreLaunchDecision.Proceed -> {
                    launches++
                    ReviewLaunchOutcome.Launched
                }
                ReviewPreLaunchDecision.Deferred -> ReviewLaunchOutcome.Deferred
                ReviewPreLaunchDecision.Failed -> ReviewLaunchOutcome.Failed
            }
        }
    }

    private class BlockingLauncher : ReviewPromptLauncher {
        private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            return when (beforeNativeLaunch()) {
                ReviewPreLaunchDecision.Proceed -> {
                    gate.await()
                    ReviewLaunchOutcome.Launched
                }
                ReviewPreLaunchDecision.Deferred -> ReviewLaunchOutcome.Deferred
                ReviewPreLaunchDecision.Failed -> ReviewLaunchOutcome.Failed
            }
        }
        fun finish() {
            gate.complete(Unit)
        }
    }

    private class CancellingLauncher : ReviewPromptLauncher {
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            throw CancellationException("cancelled")
        }
    }

    private class DeferredLauncher : ReviewPromptLauncher {
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome =
            ReviewLaunchOutcome.Deferred
    }

    private class CancellableBeforePrelaunchLauncher : ReviewPromptLauncher {
        val entered = CompletableDeferred<Unit>()
        private val never = CompletableDeferred<Unit>()
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            entered.complete(Unit)
            never.await()
            return ReviewLaunchOutcome.Launched
        }
    }

    private class CancellableAfterPrelaunchLauncher : ReviewPromptLauncher {
        val prelaunchCompleted = CompletableDeferred<Unit>()
        private val never = CompletableDeferred<Unit>()
        override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome {
            return when (beforeNativeLaunch()) {
                ReviewPreLaunchDecision.Proceed -> {
                    prelaunchCompleted.complete(Unit)
                    never.await()
                    ReviewLaunchOutcome.Launched
                }
                ReviewPreLaunchDecision.Deferred -> ReviewLaunchOutcome.Deferred
                ReviewPreLaunchDecision.Failed -> ReviewLaunchOutcome.Failed
            }
        }
    }

    private class ThrowingPreferenceStore(private val reads: Map<String, String>) : PreferenceStore {
        override fun string(key: String): String? = reads[key]
        override fun putString(key: String, value: String) {
            error("Preference write failed")
        }
        override fun boolean(key: String): Boolean? = null
        override fun putBoolean(key: String, value: Boolean) = Unit
    }

    private fun eligibleStateJson(): String =
        """{"firstUseAtMillis":0,"eventIds":["a"],"eventTimesMillis":[0],"lastAttemptedAtMillis":null}"""
}
