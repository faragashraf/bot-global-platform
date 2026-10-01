package com.ashraffarag.sentricam.camera.domain

/** Surface-rotation quadrants without an Android framework dependency. */
enum class CameraRotationQuadrant {
    ROTATION_0,
    ROTATION_90,
    ROTATION_180,
    ROTATION_270,
}

/**
 * Selects the orientation CameraX should target.
 *
 * Physical orientation is authoritative as soon as the sensor has supplied a stable quadrant.
 * This follows CameraX's locked-orientation guidance without depending on OEM-specific lock state.
 * Display rotation is used only during cold start or while physical orientation is unknown.
 */
object CameraTargetRotationPolicy {
    fun select(
        displayRotation: CameraRotationQuadrant,
        physicalRotation: CameraRotationQuadrant?,
    ): CameraRotationQuadrant = physicalRotation ?: displayRotation
}

/**
 * Quantizes raw physical orientation with the same 10-degree hysteresis used by CameraX.
 *
 * Raw sensor angles increase in the opposite direction to Android Surface rotation, hence the
 * intentional 90/270 mapping. Values in the boundary gaps retain the previous quadrant.
 */
class PhysicalOrientationQuantizer {
    private var current: CameraRotationQuadrant? = null

    fun update(orientationDegrees: Int): CameraRotationQuadrant? {
        if (orientationDegrees !in 0..359) return current
        current = if (current == null) {
            initialQuadrant(orientationDegrees)
        } else {
            hysteresisQuadrant(orientationDegrees, current!!)
        }
        return current
    }

    fun reset() {
        current = null
    }

    private fun initialQuadrant(degrees: Int): CameraRotationQuadrant = when (degrees) {
        in 0..44, in 315..359 -> CameraRotationQuadrant.ROTATION_0
        in 45..134 -> CameraRotationQuadrant.ROTATION_270
        in 135..224 -> CameraRotationQuadrant.ROTATION_180
        else -> CameraRotationQuadrant.ROTATION_90
    }

    private fun hysteresisQuadrant(
        degrees: Int,
        previous: CameraRotationQuadrant,
    ): CameraRotationQuadrant = when (degrees) {
        in 0..39, in 320..359 -> CameraRotationQuadrant.ROTATION_0
        in 50..129 -> CameraRotationQuadrant.ROTATION_270
        in 140..219 -> CameraRotationQuadrant.ROTATION_180
        in 230..309 -> CameraRotationQuadrant.ROTATION_90
        else -> previous
    }
}
