package com.ashraffarag.sentricam.live.android

import com.ashraffarag.sentricam.live.capability.CameraFrameConsumer
import com.ashraffarag.sentricam.live.capability.CameraStreamController
import com.ashraffarag.sentricam.motion.android.AndroidMotionFrameSource

/** Shares the active CameraX ImageAnalysis pipeline with Live View. */
class CameraXStreamController(
    private val frameSource: AndroidMotionFrameSource,
) : CameraStreamController {
    override fun start(consumer: CameraFrameConsumer): Boolean =
        frameSource.attachLiveConsumer(consumer::onFrame)

    override fun stop() = frameSource.detachLiveConsumer()
}
