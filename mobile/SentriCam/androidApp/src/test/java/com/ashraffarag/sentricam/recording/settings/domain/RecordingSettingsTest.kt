package com.ashraffarag.sentricam.recording.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSettingsTest {
    @Test
    fun defaultsPreserveCurrentRecordingBehavior() {
        val settings = RecordingSettings()

        assertEquals(RecordingVideoQuality.HD, settings.videoQuality)
        assertTrue(settings.audioEnabled)
        assertEquals(RecordingCamera.REAR, settings.camera)
        assertFalse(settings.overlay.showDateTime)
    }

    @Test
    fun settingsCanBeCopiedAsOneImmutableValue() {
        val settings = RecordingSettings().copy(
            videoQuality = RecordingVideoQuality.FULL_HD,
            audioEnabled = false,
            camera = RecordingCamera.FRONT,
            overlay = RecordingOverlayConfiguration(enabled = true),
        )

        assertEquals(RecordingVideoQuality.FULL_HD, settings.videoQuality)
        assertFalse(settings.audioEnabled)
        assertEquals(RecordingCamera.FRONT, settings.camera)
        assertTrue(settings.overlay.showDateTime)
    }
}
