package com.ashraffarag.sentricam.recording.overlay.domain

enum class OverlayOutputTarget {
    PREVIEW,
    RECORDED_VIDEO,
}

data class RecordingOverlayLayerPolicy(
    val cameraImageMirrored: Boolean,
    val overlayMirrored: Boolean,
) {
    companion object {
        fun resolve(frontCamera: Boolean, target: OverlayOutputTarget): RecordingOverlayLayerPolicy =
            RecordingOverlayLayerPolicy(
                cameraImageMirrored = frontCamera && target == OverlayOutputTarget.PREVIEW,
                overlayMirrored = false,
            )
    }
}
