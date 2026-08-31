package com.ashraffarag.sentricam.capability.domain

import com.ashraffarag.sentricam.capability.presentation.CapabilityAccessKind
import com.ashraffarag.sentricam.capability.presentation.CapabilityAccessUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityTest {
    @Test
    fun developmentPolicyKeepsImplementedFeaturesAvailable() {
        val service = DevelopmentEntitlementService()
        listOf(
            AppCapability.MANUAL_RECORDING,
            AppCapability.RECORDING_LIBRARY,
            AppCapability.RECORDING_PROFILES,
            AppCapability.SEGMENTED_RECORDING,
            AppCapability.STORAGE_MONITORING,
            AppCapability.BASIC_MOTION_DETECTION,
            AppCapability.ADVANCED_MOTION_SENSITIVITY,
            AppCapability.MONITORING_SERVICE,
            AppCapability.NOTIFICATION_ACTIONS,
        ).forEach { assertEquals(CapabilityAccess.Available, service.getAccess(it)) }
    }

    @Test
    fun futureDetectionAndRemoteCapabilitiesAreComingSoon() {
        val service = DevelopmentEntitlementService()
        listOf(
            AppCapability.SMART_PERSON_DETECTION,
            AppCapability.ANIMAL_DETECTION,
            AppCapability.VEHICLE_DETECTION,
            AppCapability.KNOWN_PERSON_RECOGNITION,
            AppCapability.REMOTE_CONTROL,
            AppCapability.CLOUD_UPLOAD,
        ).forEach { assertEquals(CapabilityAccess.ComingSoon, service.getAccess(it)) }
    }

    @Test
    fun guardPreventsActionOutsideUiAndPreservesStructuredAccess() {
        var executed = false
        val result = CapabilityGuard(DevelopmentEntitlementService()).execute(
            AppCapability.SMART_PERSON_DETECTION,
        ) {
            executed = true
        }
        assertFalse(executed)
        assertEquals(
            CapabilityAccess.ComingSoon,
            (result as GuardedActionResult.Rejected).access,
        )
    }

    @Test
    fun unsupportedDeviceMapsToReusableDisabledUiState() {
        val access = CapabilityAccess.UnsupportedOnDevice("camera_combination")
        val service = DevelopmentEntitlementService(mapOf(AppCapability.BASIC_MOTION_DETECTION to access))
        assertEquals(access, service.getAccess(AppCapability.BASIC_MOTION_DETECTION))
        val ui = CapabilityAccessUiMapper.map(access)
        assertEquals(CapabilityAccessKind.UNSUPPORTED, ui.kind)
        assertFalse(ui.isActionEnabled)
        assertTrue(CapabilityAccessUiMapper.map(CapabilityAccess.Available).isActionEnabled)
    }
}
