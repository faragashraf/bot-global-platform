package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.presence.PresenceEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CallableParticipant(
    val membershipId: String,
    val displayName: String,
    val availability: CallingParticipantAvailability = CallingParticipantAvailability.Offline,
    val presenceEvidence: PresenceEvidence? = null,
    val presenceObservedAtEpochMillis: Long? = null,
    val presenceFullyCovered: Boolean? = null,
)

enum class CallingParticipantAvailability { Online, Reachable, Offline }

interface CallingDirectory {
    suspend fun loadCallableParticipants(): List<CallableParticipant>
}

object UnavailableCallingDirectory : CallingDirectory {
    override suspend fun loadCallableParticipants(): List<CallableParticipant> =
        error("Calling directory is unavailable.")
}

enum class CallingDirectoryStatus { Idle, Loading, Ready, Empty, Error }

data class CallingDirectorySnapshot(
    val status: CallingDirectoryStatus = CallingDirectoryStatus.Idle,
    val participants: List<CallableParticipant> = emptyList(),
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
)

class CallingDirectoryController(
    private val directory: CallingDirectory,
) {
    private val refreshMutex = Mutex()
    private val generation = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(CallingDirectorySnapshot())
    val state: StateFlow<CallingDirectorySnapshot> = mutableState.asStateFlow()

    suspend fun refresh(currentMembershipId: String): CallingDirectorySnapshot {
        val requestGeneration = generation.value
        return refreshMutex.withLock {
            if (requestGeneration != generation.value) return@withLock mutableState.value
            val previous = mutableState.value
            val canRefreshInBackground = previous.status in setOf(
                CallingDirectoryStatus.Ready,
                CallingDirectoryStatus.Empty,
            )
            mutableState.value = if (canRefreshInBackground) {
                previous.copy(isRefreshing = true, refreshFailed = false)
            } else {
                CallingDirectorySnapshot(
                    status = CallingDirectoryStatus.Loading,
                    isRefreshing = true,
                )
            }
            try {
                val participants = directory.loadCallableParticipants()
                    .asSequence()
                    .filter { participant ->
                        participant.membershipId.isNotBlank() &&
                            participant.displayName.isNotBlank() &&
                            participant.membershipId != currentMembershipId
                    }
                    .distinctBy(CallableParticipant::membershipId)
                    .sortedWith(
                        compareBy<CallableParticipant> { it.displayName.lowercase() }
                            .thenBy(CallableParticipant::membershipId),
                    )
                    .toList()
                CallingDirectorySnapshot(
                    status = if (participants.isEmpty()) {
                        CallingDirectoryStatus.Empty
                    } else {
                        CallingDirectoryStatus.Ready
                    },
                    participants = participants,
                ).also { if (requestGeneration == generation.value) mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (canRefreshInBackground) {
                    previous.copy(isRefreshing = false, refreshFailed = true)
                } else {
                    CallingDirectorySnapshot(CallingDirectoryStatus.Error)
                }
                    .also { if (requestGeneration == generation.value) mutableState.value = it }
            }
        }
    }

    fun clear() {
        generation.update { it + 1 }
        mutableState.value = CallingDirectorySnapshot()
    }
}
