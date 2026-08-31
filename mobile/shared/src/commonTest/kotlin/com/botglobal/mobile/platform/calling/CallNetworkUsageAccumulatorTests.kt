package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceMediaStats
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallNetworkUsageAccumulatorTests {
    @Test fun delta_total_and_final_summary_are_stable() {
        val usage = CallNetworkUsageAccumulator()
        usage.markConnected(1_000)
        usage.update(sample(1, 100, 200))
        val current = usage.update(sample(1, 350, 650))
        assertEquals(250, current.bytesSent)
        assertEquals(450, current.bytesReceived)
        assertEquals(700, current.totalBytes)
        val final = usage.finish(61_000)
        assertTrue(final.isFinal)
        assertEquals(1_000, final.connectedAtEpochMillis)
        assertEquals(61_000, final.endedAtEpochMillis)
        assertEquals(60, final.connectedDurationSeconds)
        assertEquals(700.0, final.bytesPerConnectedMinute)
        assertEquals(final, usage.finish(121_000))
    }

    @Test fun reconnect_aggregates_segments_without_double_counting() {
        val usage = CallNetworkUsageAccumulator()
        usage.update(sample(1, 10, 20)); usage.update(sample(1, 110, 220))
        usage.update(sample(2, 5, 7)); val current = usage.update(sample(2, 55, 107))
        assertEquals(150, current.bytesSent)
        assertEquals(300, current.bytesReceived)
    }

    @Test fun counter_decrease_never_becomes_negative() {
        val usage = CallNetworkUsageAccumulator()
        usage.update(sample(1, 100, 100)); usage.update(sample(1, 200, 300))
        val current = usage.update(sample(1, 20, 30))
        assertTrue(current.bytesSent >= 0)
        assertTrue(current.bytesReceived >= 0)
    }

    @Test fun unavailable_stats_remain_explicitly_unknown() {
        val current = CallNetworkUsageAccumulator().update(VoiceRoomSnapshot())
        assertFalse(current.measurementAvailable)
        assertEquals(0, current.totalBytes)
        assertNull(current.bytesPerConnectedMinute)
    }

    @Test fun zero_or_subsecond_connected_duration_has_no_efficiency_rate() {
        val usage = CallNetworkUsageAccumulator()
        usage.markConnected(10_000)
        usage.update(sample(1, 0, 0))
        usage.update(sample(1, 100, 200))

        val final = usage.finish(10_999)

        assertEquals(0, final.connectedDurationSeconds)
        assertEquals(300, final.totalBytes)
        assertNull(final.bytesPerConnectedMinute)
    }

    @Test fun reconnect_preserves_usage_and_excludes_inactive_gap_from_connected_rate() {
        val usage = CallNetworkUsageAccumulator()
        usage.markConnected(5_000)
        usage.update(sample(1, 0, 0)); usage.update(sample(1, 600, 300))
        usage.markMediaInactive(25_000)
        usage.markConnected(35_000)
        usage.update(sample(2, 0, 0)); usage.update(sample(2, 300, 600))

        val final = usage.finish(65_000)

        assertEquals(5_000, final.connectedAtEpochMillis)
        assertEquals(50, final.connectedDurationSeconds)
        assertEquals(1_800, final.totalBytes)
        assertEquals(2_160.0, final.bytesPerConnectedMinute)
    }

    private fun sample(generation: Long, sent: Long, received: Long) = VoiceRoomSnapshot(
        generation = generation,
        stats = VoiceMediaStats(outboundBytes = sent, inboundBytes = received, available = true),
    )
}
