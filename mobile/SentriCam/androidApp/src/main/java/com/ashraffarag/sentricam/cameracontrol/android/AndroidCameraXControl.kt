package com.ashraffarag.sentricam.cameracontrol.android

import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlHardware
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import com.ashraffarag.sentricam.cameracontrol.capability.NoOpCameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlIds
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.live.android.AndroidPreviewController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only adapter allowed to mutate the active CameraX CameraControl. */
class AndroidCameraXControl(
    private val camera: Camera,
    private val preview: AndroidPreviewController,
    private val requestReconfigure: suspend () -> Boolean,
    private val logger: CameraControlLogger = NoOpCameraControlLogger,
) : CameraControlHardware {
    override suspend fun apply(settings: CameraControlSettings, control: String): CameraHardwareResult {
        logger.info("event=camerax_started control=$control")
        return try {
            when (control) {
            CameraControlIds.ZOOM -> setZoom(settings.zoom)
            CameraControlIds.TORCH -> setTorch(settings.torch)
            CameraControlIds.EXPOSURE -> setExposure(settings.exposureCompensation)
            CameraControlIds.PREVIEW -> preview.setPresentation(settings.preview)
            CameraControlIds.NIGHT_PROFILE -> {
                applyNightProfile(settings)
                if (!requestReconfigure()) return CameraHardwareResult(false, true, "camera_reconfigure_failed")
            }
            CameraControlIds.LENS,
            CameraControlIds.FPS,
            CameraControlIds.RESOLUTION,
            CameraControlIds.BITRATE,
            CameraControlIds.QUALITY,
            CameraControlIds.DATE_TIME_OVERLAY,
            -> if (!requestReconfigure()) return CameraHardwareResult(false, true, "camera_reconfigure_failed")
            CameraControlIds.RESTORE -> {
                setZoom(settings.zoom)
                setTorch(settings.torch)
                setExposure(effectiveExposure(settings))
                preview.setPresentation(settings.preview)
            }
                else -> return CameraHardwareResult(false, code = "unsupported_camera_control")
            }
            CameraHardwareResult(true).also { logger.info("event=camerax_finished control=$control result=${it.code}") }
        } catch (failure: Exception) {
            val result = CameraHardwareFailureMapper.map(failure)
            result.also {
                val root = generateSequence(failure as Throwable?) { it.cause }.lastOrNull()
                logger.info(
                    "event=camerax_finished control=$control result=${it.code} " +
                        "reason=${failure::class.java.name} root=${root?.javaClass?.name}",
                )
            }
        }
    }

    private suspend fun setZoom(value: Double) {
        val state = camera.cameraInfo.zoomState.value ?: error("zoom_state_unavailable")
        require(value >= state.minZoomRatio && value <= state.maxZoomRatio)
        await { camera.cameraControl.setZoomRatio(value.toFloat()).get(5, TimeUnit.SECONDS) }
    }

    private suspend fun setTorch(enabled: Boolean) {
        require(!enabled || camera.cameraInfo.hasFlashUnit())
        await { camera.cameraControl.enableTorch(enabled).get(5, TimeUnit.SECONDS) }
        val actual = camera.cameraInfo.torchState.value == TorchState.ON
        check(actual == enabled) { "torch_state_mismatch" }
        logger.info("event=camerax_torch_state requested=$enabled actual=$actual")
    }

    private suspend fun setExposure(index: Int) {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        require(index in range)
        await { camera.cameraControl.setExposureCompensationIndex(index).get(5, TimeUnit.SECONDS) }
    }

    private suspend fun applyNightProfile(settings: CameraControlSettings) {
        setTorch(false)
        setExposure(effectiveExposure(settings))
    }

    private fun effectiveExposure(settings: CameraControlSettings): Int {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        return when (settings.nightProfile) {
            CameraControlValues.NIGHT -> maxOf(1, range.upper).coerceIn(range.lower, range.upper)
            CameraControlValues.DAY,
            CameraControlValues.INDOOR,
            CameraControlValues.OUTDOOR,
            -> 0.coerceIn(range.lower, range.upper)
            else -> settings.exposureCompensation.coerceIn(range.lower, range.upper)
        }
    }

    private suspend fun await(block: () -> Unit) = withContext(Dispatchers.IO) { block() }
}
