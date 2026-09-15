package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class NativeVoiceCleanupState { Pending, InProgress, Completed }

internal data class NativeVoiceCleanupStep(
    val id: String,
    val requires: Set<String> = emptySet(),
    val action: () -> Unit,
)

/**
 * Runs dependency-ordered native cleanup steps to convergence while continuing
 * independent chains. Successful steps are remembered, failed steps remain
 * pending, and concurrent close calls serialize.
 */
internal class RetryableNativeVoiceCleanup(
    private val steps: List<NativeVoiceCleanupStep>,
) {
    private val mutex = Mutex()
    private val completedSteps = mutableSetOf<String>()

    init {
        val ids = steps.map { it.id }
        require(ids.distinct().size == ids.size) { "Native cleanup step identifiers must be unique." }
        val available = mutableSetOf<String>()
        steps.forEach { step ->
            require(available.containsAll(step.requires)) {
                "Native cleanup step dependencies must precede the dependent step."
            }
            available += step.id
        }
    }

    var state: NativeVoiceCleanupState = NativeVoiceCleanupState.Pending
        private set

    suspend fun run() = mutex.withLock {
        if (state == NativeVoiceCleanupState.Completed) return@withLock
        state = NativeVoiceCleanupState.InProgress
        var firstFailure: Throwable? = null
        steps.forEach { step ->
            if (step.id !in completedSteps && completedSteps.containsAll(step.requires)) {
                try {
                    step.action()
                    completedSteps += step.id
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                    else firstFailure.addSuppressed(error)
                }
            }
        }
        state = if (completedSteps.size == steps.size) {
            NativeVoiceCleanupState.Completed
        } else {
            NativeVoiceCleanupState.Pending
        }
        firstFailure?.let { throw it }
    }
}
