package com.ashraffarag.sentricam.smartdetection.domain

import com.ashraffarag.sentricam.capability.domain.CapabilityGuard
import com.ashraffarag.sentricam.capability.domain.DevelopmentEntitlementService
import com.ashraffarag.sentricam.capability.domain.GuardedActionResult
import com.ashraffarag.sentricam.smartdetection.capability.GuardedSmartDetectionCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDetectionContractsTest {
    private val engine = DefaultDetectionRulesEngine()

    @Test
    fun rulesSupportIgnoreRecordAndNotifyByTypeOrIdentity() {
        val known = DetectionIdentity("person-a", "A", IdentityKind.KNOWN)
        val event = DetectionEvent(
            "event-1",
            100L,
            listOf(
                DetectionSubject(SmartDetectionType.ANIMAL, 0.9),
                DetectionSubject(SmartDetectionType.PERSON, 0.9, known),
            ),
        )
        val ignoreAnimal = DetectionRule(
            "ignore-animals",
            10,
            DetectionSelector(types = setOf(SmartDetectionType.ANIMAL)),
            setOf(DetectionAction.IGNORE),
        )
        val notifyKnown = DetectionRule(
            "notify-person-a",
            20,
            DetectionSelector(identityIds = setOf("person-a")),
            setOf(DetectionAction.RECORD, DetectionAction.NOTIFY),
        )

        val decision = engine.decide(event, listOf(ignoreAnimal, notifyKnown))
        assertTrue(decision.shouldIgnore)
        assertFalse(decision.shouldRecord)
        assertFalse(decision.shouldNotify)
        assertEquals(listOf("notify-person-a", "ignore-animals"), decision.matchedRuleIds)
    }

    @Test
    fun unknownPersonCanRecordAndNotifyWithoutClassifierImplementation() {
        val unknown = DetectionSubject(
            SmartDetectionType.PERSON,
            0.8,
            DetectionIdentity("unknown-event", null, IdentityKind.UNKNOWN),
        )
        val rule = DetectionRule(
            "unknown-person",
            10,
            DetectionSelector(
                types = setOf(SmartDetectionType.PERSON),
                identityKinds = setOf(IdentityKind.UNKNOWN),
            ),
            setOf(DetectionAction.RECORD, DetectionAction.NOTIFY),
        )
        val decision = engine.decide(DetectionEvent("event", 1L, listOf(unknown)), listOf(rule))
        assertTrue(decision.shouldRecord)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun comingSoonGuardDoesNotInvokeSmartEngine() = runBlocking {
        var calls = 0
        val smartEngine = object : SmartDetectionEngine {
            override suspend fun analyze(eventId: String, frameTimestampMillis: Long): SmartDetectionResult {
                calls++
                return SmartDetectionResult.NoSubjects
            }
        }
        val capability = GuardedSmartDetectionCapability(
            CapabilityGuard(DevelopmentEntitlementService()),
            smartEngine,
        )
        val result = capability.analyzePerson("event", 1L)
        assertTrue(result is GuardedActionResult.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun privacyFoundationRequiresLocalEncryptedConsentAwareHandling() {
        val policy = SentriCamRecognitionPrivacy.policy
        assertTrue(policy.localFirst)
        assertFalse(policy.storesRawFaces)
        assertTrue(policy.encryptsIdentityTemplates)
        assertTrue(policy.requiresUploadConsent)
        assertTrue(policy.deleteIdentityRemovesDerivedData)
        assertTrue(policy.requiresActiveRecognitionIndicator)
    }
}
