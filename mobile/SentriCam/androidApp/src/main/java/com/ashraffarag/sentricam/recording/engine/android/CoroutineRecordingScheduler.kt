package com.ashraffarag.sentricam.recording.engine.android

import com.ashraffarag.sentricam.recording.engine.capability.RecordingScheduledTask
import com.ashraffarag.sentricam.recording.engine.capability.RecordingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CoroutineRecordingScheduler(
    private val scope: CoroutineScope,
) : RecordingScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): RecordingScheduledTask {
        val job = scope.launch {
            delay(delayMillis.coerceAtLeast(0L))
            task()
        }
        return RecordingScheduledTask { job.cancel() }
    }
}
