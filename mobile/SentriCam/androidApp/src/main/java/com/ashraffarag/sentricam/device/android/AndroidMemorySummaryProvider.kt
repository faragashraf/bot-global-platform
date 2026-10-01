package com.ashraffarag.sentricam.device.android

import android.app.ActivityManager
import android.content.Context
import com.ashraffarag.sentricam.device.domain.MemorySummary
import com.ashraffarag.sentricam.device.health.MemorySummaryProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidMemorySummaryProvider(context: Context) : MemorySummaryProvider {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)
    private val mutableState = MutableStateFlow(readState())
    override val state: StateFlow<MemorySummary> = mutableState.asStateFlow()

    override fun refresh() {
        mutableState.value = readState()
    }

    private fun readState(): MemorySummary = runCatching {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        MemorySummary(
            availableBytes = info.availMem.takeIf { it > 0L },
            totalBytes = info.totalMem.takeIf { it > 0L },
            isLow = info.lowMemory,
        )
    }.getOrDefault(MemorySummary())
}
