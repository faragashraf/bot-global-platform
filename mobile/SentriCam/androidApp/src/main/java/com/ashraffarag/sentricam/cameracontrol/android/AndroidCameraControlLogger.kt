package com.ashraffarag.sentricam.cameracontrol.android

import android.util.Log
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlLogger

class AndroidCameraControlLogger : CameraControlLogger {
    override fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "SentriCamCameraControl"
    }
}
