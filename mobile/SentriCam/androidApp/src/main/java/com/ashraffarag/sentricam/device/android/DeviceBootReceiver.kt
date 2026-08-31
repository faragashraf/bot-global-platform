package com.ashraffarag.sentricam.device.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class DeviceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                DeviceConnectivityService.intent(context, DeviceConnectivityService.ACTION_RESTORE_RUNTIME),
            )
        }.onFailure { failure ->
            Log.w(TAG, "Automatic runtime restore could not be scheduled", failure)
        }
    }

    private companion object {
        const val TAG = "DeviceRecovery"
    }
}
