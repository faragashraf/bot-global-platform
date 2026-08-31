package com.ashraffarag.sentricam.camera.domain

data class PixelDimensions(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0)
        require(height >= 0)
    }

    val isPortrait: Boolean
        get() = height > width
}

enum class OutputRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
    ;

    companion object {
        fun fromDegrees(degrees: Int): OutputRotation {
            val normalized = ((degrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION
            return entries.firstOrNull { it.degrees == normalized }
                ?: throw IllegalArgumentException("Rotation must be a multiple of 90 degrees")
        }

        private const val FULL_ROTATION = 360
    }
}

object CameraOutputGeometry {
    fun effectiveDimensions(
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
    ): PixelDimensions {
        val rotation = OutputRotation.fromDegrees(rotationDegrees)
        return when (rotation) {
            OutputRotation.ROTATION_90,
            OutputRotation.ROTATION_270,
            -> PixelDimensions(encodedHeight, encodedWidth)

            OutputRotation.ROTATION_0,
            OutputRotation.ROTATION_180,
            -> PixelDimensions(encodedWidth, encodedHeight)
        }
    }
}
