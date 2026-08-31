package com.ashraffarag.sentricam.motion.android

import com.ashraffarag.sentricam.motion.capability.MotionScheduledTask
import com.ashraffarag.sentricam.motion.capability.MotionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CoroutineMotionScheduler(
    private val scope: CoroutineScope,
) : MotionScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): MotionScheduledTask {
        val job = scope.launch {
            delay(delayMillis.coerceAtLeast(0L))
            task()
        }
        return MotionScheduledTask(job::cancel)
    }
}
