package com.ashraffarag.sentricam.device.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidRegistrationRequestMapperTest {
    @Test
    fun deviceIdentityMapsToVersionedNonPrivilegedRegistrationRequest() {
        val request = AndroidRegistrationRequestMapper().map(identity(), capabilities(), NOW)

        assertEquals(INSTALLATION_ID, request.identity.installationId)
        assertEquals("Samsung", request.identity.manufacturer)
        assertEquals("SM-S921B", request.identity.model)
        assertEquals("Android", request.identity.platform)
        assertEquals("16", request.identity.operatingSystemVersion)
        assertEquals("1.2.3", request.identity.appVersion)
        assertEquals("42", request.identity.appBuild)
        assertEquals(36, request.identity.apiLevel)
        assertEquals(REGISTRATION_SCHEMA_VERSION, request.clientRegistrationSchemaVersion)
        assertFalse(request.toString().contains("not-sent"))
    }

    @Test
    fun capabilityMappingIncludesOnlyStableAvailableImplementations() {
        val names = AndroidRegistrationRequestMapper().map(identity(), capabilities(), NOW)
            .capabilities.map { it.name }

        assertEquals(
            listOf("manual-recording", "monitoring-service", "motion-detection", "recording-library"),
            names,
        )
        assertFalse(names.any { "smart" in it || "multiple-cameras" == it })
    }
}
