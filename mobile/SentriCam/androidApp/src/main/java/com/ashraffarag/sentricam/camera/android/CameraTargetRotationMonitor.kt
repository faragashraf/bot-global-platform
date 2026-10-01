package com.ashraffarag.sentricam.camera.android

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import com.ashraffarag.sentricam.camera.domain.CameraRotationQuadrant
import com.ashraffarag.sentricam.camera.domain.CameraTargetRotationPolicy
import com.ashraffarag.sentricam.camera.domain.PhysicalOrientationQuantizer
import com.ashraffarag.sentricam.live.android.LiveViewDiagnostics

/** Keeps all CameraX owners aligned with display rotation or locked-screen physical orientation. */
class CameraTargetRotationMonitor(
    context: Context,
    private val view: View?,
    private val onRotationChanged: (Int) -> Unit,
) {
    constructor(
        context: Context,
        onRotationChanged: (Int) -> Unit,
    ) : this(context, null, onRotationChanged)

    private val applicationContext = context.applicationContext
    private val displayManager = applicationContext.getSystemService(
        Context.DISPLAY_SERVICE,
    ) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val physicalOrientation = PhysicalOrientationQuantizer()
    private var started = false
    private var latestPhysicalRotation: CameraRotationQuadrant? = null
    private var lastRotation: Int? = null
    private var settingsObserverRegistered = false

    private val orientationListener = object : OrientationEventListener(applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val previous = latestPhysicalRotation
            latestPhysicalRotation = physicalOrientation.update(orientation)
            if (previous != latestPhysicalRotation) {
                LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                    "event=physical_bucket raw=$orientation previous=$previous current=$latestPhysicalRotation " +
                        "display=${currentDisplayRotation()} autoRotate=${isAutoRotateEnabled()}"
                }
            }
            dispatchAuthoritativeRotation()
        }
    }

    private val rotationSettingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                "event=rotation_setting_changed autoRotate=${isAutoRotateEnabled()} " +
                    "display=${currentDisplayRotation()} physical=$latestPhysicalRotation"
            }
            dispatchAuthoritativeRotation()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == observedDisplayId()) {
                LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
                    "event=display_changed displayId=$displayId rotation=${currentDisplayRotation()} " +
                        "autoRotate=${isAutoRotateEnabled()} physical=$latestPhysicalRotation"
                }
                dispatchAuthoritativeRotation()
            }
        }
    }

    fun currentDisplayRotation(): Int =
        view?.display?.rotation
            ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    fun currentTargetRotation(): Int = lastRotation ?: authoritativeRotation()

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        runCatching {
            applicationContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                rotationSettingObserver,
            )
            settingsObserverRegistered = true
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
            "event=monitor_start display=${currentDisplayRotation()} autoRotate=${isAutoRotateEnabled()} " +
                "sensorAvailable=${orientationListener.canDetectOrientation()}"
        }
        dispatchAuthoritativeRotation()
    }

    fun stop() {
        if (!started) return
        displayManager.unregisterDisplayListener(displayListener)
        if (settingsObserverRegistered) {
            applicationContext.contentResolver.unregisterContentObserver(rotationSettingObserver)
            settingsObserverRegistered = false
        }
        orientationListener.disable()
        physicalOrientation.reset()
        latestPhysicalRotation = null
        started = false
        lastRotation = null
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) { "event=monitor_stop" }
    }

    private fun dispatchAuthoritativeRotation() = dispatch(authoritativeRotation())

    private fun authoritativeRotation(): Int {
        val selected = CameraTargetRotationPolicy.select(
            displayRotation = currentDisplayRotation().toQuadrant(),
            physicalRotation = latestPhysicalRotation,
        )
        return selected.toSurfaceRotation()
    }

    private fun isAutoRotateEnabled(): Boolean = runCatching {
        Settings.System.getInt(
            applicationContext.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            AUTO_ROTATE_ENABLED,
        ) == AUTO_ROTATE_ENABLED
    }.getOrDefault(true)

    private fun dispatch(rotation: Int) {
        if (rotation == lastRotation) return
        LiveViewDiagnostics.log(LiveViewDiagnostics.ORIENTATION) {
            "event=target_rotation previous=$lastRotation target=$rotation " +
                "display=${currentDisplayRotation()} physical=$latestPhysicalRotation " +
                "autoRotate=${isAutoRotateEnabled()}"
        }
        lastRotation = rotation
        onRotationChanged(rotation)
    }

    private fun observedDisplayId(): Int = view?.display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun Int.toQuadrant(): CameraRotationQuadrant = when (this) {
        Surface.ROTATION_90 -> CameraRotationQuadrant.ROTATION_90
        Surface.ROTATION_180 -> CameraRotationQuadrant.ROTATION_180
        Surface.ROTATION_270 -> CameraRotationQuadrant.ROTATION_270
        else -> CameraRotationQuadrant.ROTATION_0
    }

    private fun CameraRotationQuadrant.toSurfaceRotation(): Int = when (this) {
        CameraRotationQuadrant.ROTATION_0 -> Surface.ROTATION_0
        CameraRotationQuadrant.ROTATION_90 -> Surface.ROTATION_90
        CameraRotationQuadrant.ROTATION_180 -> Surface.ROTATION_180
        CameraRotationQuadrant.ROTATION_270 -> Surface.ROTATION_270
    }

    private companion object {
        const val AUTO_ROTATE_ENABLED = 1
    }
}
