package com.ashraffarag.sentricam.live.capability

import com.ashraffarag.sentricam.camera.domain.CameraLensFacing
import com.ashraffarag.sentricam.camera.domain.CameraOutputGeometry
import com.ashraffarag.sentricam.camera.domain.OutputRotation
import com.ashraffarag.sentricam.camera.domain.PixelDimensions

/** The single transform description applied to a remote Live View frame. */
data class LiveFrameOrientation(
    val rotationDegrees: Int,
    val encodedDimensions: PixelDimensions,
    val displayDimensions: PixelDimensions,
)

object LiveFrameOrientationResolver {
    fun resolve(
        encodedWidth: Int,
        encodedHeight: Int,
        cameraXRotationDegrees: Int,
    ): LiveFrameOrientation {
        val rotation = OutputRotation.fromDegrees(cameraXRotationDegrees)
        return LiveFrameOrientation(
            rotationDegrees = rotation.degrees,
            encodedDimensions = PixelDimensions(encodedWidth, encodedHeight),
            displayDimensions = CameraOutputGeometry.effectiveDimensions(
                encodedWidth,
                encodedHeight,
                rotation.degrees,
            ),
        )
    }
}

/** CameraX analysis pixels are unmirrored; remote monitoring keeps that objective view. */
object LiveRemoteMirrorPolicy {
    fun isMirrored(lensFacing: CameraLensFacing): Boolean = when (lensFacing) {
        CameraLensFacing.REAR,
        CameraLensFacing.FRONT,
        -> false
    }
}
