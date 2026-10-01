package com.ashraffarag.sentricam.live.android

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.ashraffarag.sentricam.live.domain.LiveCameraCapability
import com.ashraffarag.sentricam.live.domain.LiveCapabilityReadiness
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveReadinessStates
import java.time.Instant

class AndroidLiveCameraCapabilityResolver(
    context: Context,
    private val readiness: () -> LiveCapabilityReadiness = {
        LiveCapabilityReadiness(LiveReadinessStates.READY)
    },
) {
    private val cameraManager = context.applicationContext.getSystemService(CameraManager::class.java)

    fun resolve(deviceId: String): LiveDeviceCapabilities {
        val cameras = cameraManager.cameraIdList.mapNotNull { cameraId ->
            runCatching { capability(cameraManager.getCameraCharacteristics(cameraId)) }.getOrNull()
        }.distinctBy(LiveCameraCapability::lens)
        val currentReadiness = readiness()
        return LiveDeviceCapabilities(
            deviceId,
            cameras,
            true,
            Instant.now().toString(),
            currentReadiness.state,
            currentReadiness.reason,
        )
    }

    private fun capability(characteristics: CameraCharacteristics): LiveCameraCapability? {
        val lens = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            else -> return null
        }
        val configurations = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val resolutions = configurations?.getOutputSizes(ImageFormat.YUV_420_888)
            .orEmpty()
            .filter { it.width >= 320 && it.height >= 240 }
            .sortedByDescending { it.width.toLong() * it.height }
            .map { "${it.width}x${it.height}" }
            .distinct()
            .take(MAX_REPORTED_VALUES)
        val frameRates = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .map { it.upper }
            .filter { it in 1..120 }
            .distinct()
            .sorted()
            .takeLast(MAX_REPORTED_VALUES)
        val maximumZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return LiveCameraCapability(
            lens = lens,
            available = true,
            torch = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            zoom = maximumZoom > 1f,
            resolutions = resolutions,
            frameRates = frameRates,
        )
    }

    private companion object {
        const val MAX_REPORTED_VALUES = 8
    }
}
