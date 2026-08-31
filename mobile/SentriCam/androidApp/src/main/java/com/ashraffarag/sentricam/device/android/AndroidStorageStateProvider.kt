package com.ashraffarag.sentricam.device.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StatFs
import com.ashraffarag.sentricam.device.domain.StorageState
import com.ashraffarag.sentricam.device.health.StorageStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidStorageStateProvider(context: Context) : StorageStateProvider, AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(readState(false))
    override val state: StateFlow<StorageState> = mutableState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mutableState.value = readState(intent.action == Intent.ACTION_DEVICE_STORAGE_LOW)
        }
    }

    init {
        appContext.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            },
        )
    }

    override fun refresh() {
        mutableState.value = readState(mutableState.value.isLow)
    }

    override fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun readState(systemLow: Boolean): StorageState = runCatching {
        val stats = StatFs(appContext.filesDir.absolutePath)
        val available = stats.availableBytes.coerceAtLeast(0L)
        StorageState(
            availableBytes = available,
            totalBytes = stats.totalBytes.coerceAtLeast(0L),
            isLow = systemLow || available < LOW_STORAGE_BYTES,
        )
    }.getOrDefault(StorageState(isLow = systemLow))

    private companion object {
        const val LOW_STORAGE_BYTES = 500L * 1024L * 1024L
    }
}
