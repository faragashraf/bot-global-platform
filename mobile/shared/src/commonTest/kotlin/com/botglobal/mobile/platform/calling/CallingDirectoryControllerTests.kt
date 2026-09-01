package com.botglobal.mobile.platform.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallingDirectoryControllerTests {
    @Test
    fun refresh_exposes_loading_until_the_directory_completes() = runTest {
        val result = CompletableDeferred<List<CallableParticipant>>()
        val controller = CallingDirectoryController(
            object : CallingDirectory {
                override suspend fun loadCallableParticipants() = result.await()
            },
        )

        backgroundScope.launch { controller.refresh("self") }
        runCurrent()

        assertEquals(CallingDirectoryStatus.Loading, controller.state.value.status)

        result.complete(emptyList())
        runCurrent()
        assertEquals(CallingDirectoryStatus.Empty, controller.state.value.status)
    }

    @Test
    fun maps_valid_participants_deterministically_and_excludes_self() = runTest {
        val controller = CallingDirectoryController(
            FixedDirectory(
                listOf(
                    CallableParticipant("remote-b", "Beta"),
                    CallableParticipant("self", "Current user"),
                    CallableParticipant("remote-a", "Alpha"),
                    CallableParticipant("remote-a", "Stale duplicate"),
                    CallableParticipant("", "Invalid"),
                ),
            ),
        )

        val snapshot = controller.refresh("self")

        assertEquals(CallingDirectoryStatus.Ready, snapshot.status)
        assertEquals(
            listOf(
                CallableParticipant("remote-a", "Alpha"),
                CallableParticipant("remote-b", "Beta"),
            ),
            snapshot.participants,
        )
    }

    @Test
    fun empty_directory_has_an_explicit_empty_state() = runTest {
        val controller = CallingDirectoryController(FixedDirectory(emptyList()))

        val snapshot = controller.refresh("self")

        assertEquals(CallingDirectoryStatus.Empty, snapshot.status)
        assertEquals(emptyList(), snapshot.participants)
    }

    @Test
    fun failure_has_an_explicit_error_state_and_retry_can_recover() = runTest {
        val directory = RetryDirectory()
        val controller = CallingDirectoryController(directory)

        assertEquals(
            CallingDirectoryStatus.Error,
            controller.refresh("self").status,
        )
        assertEquals(
            CallingDirectoryStatus.Ready,
            controller.refresh("self").status,
        )
        assertEquals(
            listOf(CallableParticipant("remote", "Remote user")),
            controller.state.value.participants,
        )
    }

    @Test
    fun clear_removes_previous_identity_directory_state() = runTest {
        val controller = CallingDirectoryController(
            FixedDirectory(listOf(CallableParticipant("remote", "Remote user"))),
        )
        controller.refresh("self")

        controller.clear()

        assertEquals(CallingDirectorySnapshot(), controller.state.value)
    }

    private class FixedDirectory(
        private val participants: List<CallableParticipant>,
    ) : CallingDirectory {
        override suspend fun loadCallableParticipants() = participants
    }

    private class RetryDirectory : CallingDirectory {
        private var attempts = 0

        override suspend fun loadCallableParticipants(): List<CallableParticipant> {
            attempts++
            if (attempts == 1) error("Synthetic directory failure")
            return listOf(CallableParticipant("remote", "Remote user"))
        }
    }
}
