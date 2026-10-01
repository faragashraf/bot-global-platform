package com.ashraffarag.sentricam.device.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/** Debug-only, shell-authorized process-death injection for repeatable recovery acceptance. */
class RecoveryFaultInjectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KILL_PROCESS) return
        Log.w(TAG, "event=fault_injection fault=process_death")
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val ACTION_KILL_PROCESS = "com.ashraffarag.sentricam.debug.KILL_PROCESS"
        private const val TAG = "RecoveryFaultInjection"
    }
}
