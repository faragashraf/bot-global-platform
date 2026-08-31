package com.ashraffarag.sentricam.recording.playback.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerUiStateTest {
    @Test
    fun enteringAndExitingFullScreenPreservesPlaybackState() {
        val initial = VideoPlayerUiState(
            isPrepared = true,
            isPlaying = true,
            playWhenReady = true,
            positionMillis = 12_345,
            durationMillis = 60_000,
        )

        val restored = initial.enterFullScreen().exitFullScreen()

        assertEquals(initial, restored)
    }

    @Test
    fun restoredPositionSurvivesConfigurationStateRoundTrip() {
        val restored = VideoPlayerUiState.restored(
            positionMillis = 42_000,
            playWhenReady = true,
            isFullScreen = true,
        ).prepared(90_000)

        assertEquals(42_000, restored.positionMillis)
        assertTrue(restored.playWhenReady)
        assertTrue(restored.isFullScreen)
    }

    @Test
    fun completionClearsPlaybackRequestWithoutLosingDuration() {
        val completed = VideoPlayerUiState(
            isPrepared = true,
            isPlaying = true,
            playWhenReady = true,
            durationMillis = 10_000,
        ).completed()

        assertFalse(completed.isPlaying)
        assertFalse(completed.playWhenReady)
        assertEquals(10_000, completed.positionMillis)
    }
}
