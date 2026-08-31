package com.ashraffarag.sentricam.cameracontrol.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues

/** Selects an advertised Camera2 range; it never invents an exact FPS range. */
class AndroidCameraFrameRateResolver(context: Context) {
    private val cameras = context.applicationContext.getSystemService(CameraManager::class.java)

    fun resolve(lens: String, targetFramesPerSecond: Int): Range<Int>? {
        val expectedFacing = if (lens == CameraControlValues.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val ranges = cameras.cameraIdList.firstNotNullOfOrNull { id ->
            runCatching { cameras.getCameraCharacteristics(id) }.getOrNull()
                ?.takeIf { it.get(CameraCharacteristics.LENS_FACING) == expectedFacing }
                ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
        }.orEmpty()
        return ranges
            .filter { targetFramesPerSecond in it }
            .minWithOrNull(compareBy<Range<Int>>(
                { kotlin.math.abs(it.upper - targetFramesPerSecond) },
                { kotlin.math.abs(it.lower - targetFramesPerSecond) },
            ))
    }
}
