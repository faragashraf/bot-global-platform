package com.botglobal.mobile.platform.calling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CallableParticipant(
    val membershipId: String,
    val displayName: String,
    val availability: CallingParticipantAvailability = CallingParticipantAvailability.Offline,
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
)

class CallingDirectoryController(
    private val directory: CallingDirectory,
) {
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(CallingDirectorySnapshot())
    val state: StateFlow<CallingDirectorySnapshot> = mutableState.asStateFlow()

    suspend fun refresh(currentMembershipId: String): CallingDirectorySnapshot =
        refreshMutex.withLock {
            mutableState.value = CallingDirectorySnapshot(CallingDirectoryStatus.Loading)
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
                ).also { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CallingDirectorySnapshot(CallingDirectoryStatus.Error)
                    .also { mutableState.value = it }
            }
        }

    fun clear() {
        mutableState.value = CallingDirectorySnapshot()
    }
}
