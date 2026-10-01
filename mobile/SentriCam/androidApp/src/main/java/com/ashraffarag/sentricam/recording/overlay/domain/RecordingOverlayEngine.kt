package com.ashraffarag.sentricam.recording.overlay.domain

import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

interface RecordingOverlayEngine {
    fun update(configuration: RecordingOverlayConfiguration)

    fun close()
}
