package com.ashraffarag.sentricam.smartdetection.capability

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityGuard
import com.ashraffarag.sentricam.capability.domain.GuardedActionResult
import com.ashraffarag.sentricam.smartdetection.domain.SmartDetectionEngine
import com.ashraffarag.sentricam.smartdetection.domain.SmartDetectionResult

class GuardedSmartDetectionCapability(
    private val guard: CapabilityGuard,
    private val engine: SmartDetectionEngine,
) {
    suspend fun analyzePerson(eventId: String, timestampMillis: Long): GuardedActionResult<SmartDetectionResult> =
        when (val access = guard.check(AppCapability.SMART_PERSON_DETECTION)) {
            com.ashraffarag.sentricam.capability.domain.CapabilityGuardResult.Allowed ->
                GuardedActionResult.Executed(engine.analyze(eventId, timestampMillis))
            is com.ashraffarag.sentricam.capability.domain.CapabilityGuardResult.Blocked ->
                GuardedActionResult.Rejected(access.access)
        }
}
