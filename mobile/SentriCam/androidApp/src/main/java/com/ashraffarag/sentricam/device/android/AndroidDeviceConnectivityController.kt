package com.ashraffarag.sentricam.device.android

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat

/** Starts and stops only the persistent remote-connectivity ownership boundary. */
class AndroidDeviceConnectivityController(context: Context) {
    private val appContext = context.applicationContext

    fun start(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            appContext,
            DeviceConnectivityService.intent(
                appContext,
                DeviceConnectivityService.ACTION_START_CONNECTIVITY,
            ),
        )
    }.fold(
        onSuccess = { true },
        onFailure = { failure ->
            Log.w(TAG, "Unable to start device connectivity runtime", failure)
            false
        },
    )

    fun stop(): Boolean = runCatching {
        appContext.startService(
            DeviceConnectivityService.intent(
                appContext,
                DeviceConnectivityService.ACTION_STOP_CONNECTIVITY,
            ),
        )
    }.fold(
        onSuccess = { true },
        onFailure = { failure ->
            Log.w(TAG, "Unable to stop device connectivity runtime", failure)
            false
        },
    )

    private companion object {
        const val TAG = "DeviceConnectivity"
    }
}
