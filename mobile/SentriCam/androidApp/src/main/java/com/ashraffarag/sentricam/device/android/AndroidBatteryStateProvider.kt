package com.ashraffarag.sentricam.device.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.ashraffarag.sentricam.device.domain.BatteryState
import com.ashraffarag.sentricam.device.health.BatteryStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidBatteryStateProvider(context: Context) : BatteryStateProvider, AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(BatteryState())
    override val state: StateFlow<BatteryState> = mutableState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mutableState.value = intent.toBatteryState(appContext)
        }
    }

    init {
        val sticky = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) mutableState.value = sticky.toBatteryState(appContext)
    }

    override fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun Intent.toBatteryState(context: Context): BatteryState {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val powerManager = context.getSystemService(PowerManager::class.java)
        return BatteryState(
            levelPercent = percent,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            isPowerSaveMode = powerManager?.isPowerSaveMode,
        )
    }
}
