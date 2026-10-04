package com.botglobal.mobile.platform.reviews

import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReviewTrigger {
    CompletedExperience,
    Foreground,
    ExplicitExit,
}

data class ReviewPolicy(
    val firstUseAgeMillis: Long = 3L * 24L * 60L * 60L * 1_000L,
    val minimumMeaningfulEvents: Int = 3,
    val minimumMeaningfulEventSpanMillis: Long = 3L * 24L * 60L * 60L * 1_000L,
    val cooldownAfterAttemptMillis: Long = 90L * 24L * 60L * 60L * 1_000L,
    val triggers: Set<ReviewTrigger> = setOf(ReviewTrigger.CompletedExperience, ReviewTrigger.Foreground),
    val deduplicationLimit: Int = 80,
) {
    init {
        require(firstUseAgeMillis >= 0)
        require(minimumMeaningfulEvents > 0)
        require(minimumMeaningfulEventSpanMillis >= 0)
        require(cooldownAfterAttemptMillis > 0)
        require(deduplicationLimit > 0)
    }
}

fun interface ReviewPromptLauncher {
    suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome
}

object NoOpReviewPromptLauncher : ReviewPromptLauncher {
    override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome =
        ReviewLaunchOutcome.Deferred
}

data class ReviewEvent(
    val id: String,
    val occurredAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Review event id is required." }
    }
}

sealed interface ReviewAttemptResult {
    data object NotEligible : ReviewAttemptResult
    data object Launched : ReviewAttemptResult
    data object Deferred : ReviewAttemptResult
    data object AlreadyRunning : ReviewAttemptResult
    data object Failed : ReviewAttemptResult
}

enum class ReviewLaunchOutcome {
    Launched,
    Deferred,
    Failed,
}

enum class ReviewPreLaunchDecision {
    Proceed,
    Deferred,
    Failed,
}

class ReviewCoordinator(
    private val preferenceStore: PreferenceStore,
    private val storageKey: String,
    private val policy: ReviewPolicy,
    private val launcher: ReviewPromptLauncher = NoOpReviewPromptLauncher,
    private val nowMillis: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var launchInProgress = false

    suspend fun recordMeaningfulEvent(eventId: String, occurredAtMillis: Long = nowMillis()) {
        val event = ReviewEvent(eventId, occurredAtMillis)
        lock.withLock {
            val state = loadOrDefault().withFirstUse(occurredAtMillis)
            if (state.eventIds.contains(event.id)) return
            val ids = (state.eventIds + event.id).takeLast(policy.deduplicationLimit)
            val times = (state.eventTimesMillis + event.occurredAtMillis)
                .sorted()
                .takeLast(policy.deduplicationLimit)
            saveIfPossible(state.copy(eventIds = ids, eventTimesMillis = times))
        }
    }

    suspend fun tryRequest(
        trigger: ReviewTrigger,
        isStillReady: () -> Boolean = { true },
    ): ReviewAttemptResult {
        if (trigger !in policy.triggers) return ReviewAttemptResult.NotEligible
        lock.withLock {
            if (launchInProgress) return ReviewAttemptResult.AlreadyRunning
            val now = nowMillis()
            val state = loadOrDefault().withFirstUse(now)
            if (!state.isEligible(now, policy)) {
                if (!saveIfPossible(state)) return ReviewAttemptResult.Failed
                return ReviewAttemptResult.NotEligible
            }
            launchInProgress = true
        }

        return try {
            when (launcher.requestReview { persistNativeAttemptIfReady(isStillReady) }) {
                ReviewLaunchOutcome.Launched -> ReviewAttemptResult.Launched
                ReviewLaunchOutcome.Deferred -> ReviewAttemptResult.Deferred
                ReviewLaunchOutcome.Failed -> ReviewAttemptResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ReviewAttemptResult.Failed
        } finally {
            withContext(NonCancellable) {
                lock.withLock { launchInProgress = false }
            }
        }
    }

    private suspend fun persistNativeAttemptIfReady(isStillReady: () -> Boolean): ReviewPreLaunchDecision =
        lock.withLock {
            if (!isStillReady()) return@withLock ReviewPreLaunchDecision.Deferred
            val now = nowMillis()
            val state = loadOrDefault().withFirstUse(now)
            if (saveIfPossible(state.copy(lastAttemptedAtMillis = now))) {
                ReviewPreLaunchDecision.Proceed
            } else {
                ReviewPreLaunchDecision.Failed
            }
        }

    private fun ReviewState.withFirstUse(now: Long): ReviewState =
        if (firstUseAtMillis == null) copy(firstUseAtMillis = now) else this

    private fun ReviewState.isEligible(now: Long, policy: ReviewPolicy): Boolean {
        val firstUse = firstUseAtMillis ?: return false
        if (now - firstUse < policy.firstUseAgeMillis) return false
        lastAttemptedAtMillis?.let { attempted ->
            if (now - attempted < policy.cooldownAfterAttemptMillis) return false
        }
        if (eventIds.size < policy.minimumMeaningfulEvents) return false
        val firstEvent = eventTimesMillis.minOrNull() ?: return false
        val lastEvent = eventTimesMillis.maxOrNull() ?: return false
        return lastEvent - firstEvent >= policy.minimumMeaningfulEventSpanMillis
    }

    private fun loadOrDefault(): ReviewState =
        runCatching {
            preferenceStore.string(storageKey)
                ?.let { stored -> runCatching { json.decodeFromString<ReviewState>(stored) }.getOrNull() }
                ?: ReviewState()
        }.getOrDefault(ReviewState())

    private fun saveIfPossible(state: ReviewState): Boolean =
        runCatching { saveOrThrow(state) }.isSuccess

    private fun saveOrThrow(state: ReviewState) {
        preferenceStore.putString(storageKey, json.encodeToString(state))
    }
}

@Serializable
private data class ReviewState(
    val firstUseAtMillis: Long? = null,
    val eventIds: List<String> = emptyList(),
    val eventTimesMillis: List<Long> = emptyList(),
    val lastAttemptedAtMillis: Long? = null,
)
