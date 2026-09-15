package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryableNativeVoiceCleanupTests {
    @Test
    fun failed_native_steps_do_not_skip_independent_cleanup_and_only_failures_retry() = runTest {
        val calls = IntArray(4)
        val failuresRemaining = intArrayOf(0, 1, 0, 1)
        val cleanup = RetryableNativeVoiceCleanup(
            List(4) { index ->
                NativeVoiceCleanupStep("step-$index") {
                    calls[index]++
                    if (failuresRemaining[index] > 0) {
                        failuresRemaining[index]--
                        error("native_cleanup_$index")
                    }
                }
            },
        )

        assertFailsWith<IllegalStateException> { cleanup.run() }
        assertEquals(listOf(1, 1, 1, 1), calls.toList())
        assertEquals(NativeVoiceCleanupState.Pending, cleanup.state)

        cleanup.run()
        cleanup.run()

        assertEquals(listOf(1, 2, 1, 2), calls.toList())
        assertEquals(NativeVoiceCleanupState.Completed, cleanup.state)
    }

    @Test
    fun failed_prerequisite_blocks_invalidating_disposal_until_retry_converges() = runTest {
        var disableCalls = 0
        var disposeCalls = 0
        var independentCalls = 0
        var disposed = false
        val cleanup = RetryableNativeVoiceCleanup(
            listOf(
                NativeVoiceCleanupStep("track-disabled") {
                    disableCalls++
                    check(!disposed) { "disposed track cannot be disabled" }
                    if (disableCalls == 1) error("disable_failed")
                },
                NativeVoiceCleanupStep("track-disposed", setOf("track-disabled")) {
                    disposeCalls++
                    disposed = true
                },
                NativeVoiceCleanupStep("independent") { independentCalls++ },
            ),
        )

        assertFailsWith<IllegalStateException> { cleanup.run() }
        assertEquals(1, disableCalls)
        assertEquals(0, disposeCalls)
        assertEquals(1, independentCalls)
        assertEquals(NativeVoiceCleanupState.Pending, cleanup.state)

        cleanup.run()
        cleanup.run()

        assertEquals(2, disableCalls)
        assertEquals(1, disposeCalls)
        assertEquals(1, independentCalls)
        assertEquals(NativeVoiceCleanupState.Completed, cleanup.state)
    }
}
