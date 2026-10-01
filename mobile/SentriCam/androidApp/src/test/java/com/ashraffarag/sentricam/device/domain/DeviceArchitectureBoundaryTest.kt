package com.ashraffarag.sentricam.device.domain

import com.ashraffarag.sentricam.device.command.DefaultDeviceCommandHandler
import com.ashraffarag.sentricam.monitoring.domain.DefaultMonitoringServiceController
import com.ashraffarag.sentricam.monitoring.domain.MonitoringLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceArchitectureBoundaryTest {
    @Test
    fun snapshotContainsNoAndroidCameraOrThrowableObjects() {
        val forbiddenPrefixes = listOf("android.", "androidx.")
        val forbiddenNames = listOf("Throwable", "Exception", "CameraX", "View", "Context")

        DeviceSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .forEach { field ->
                assertFalse(field.type.name, forbiddenPrefixes.any(field.type.name::startsWith))
                assertFalse(field.type.name, forbiddenNames.any(field.type.name::contains))
            }
    }

    @Test
    fun commandHandlerHasNoSignalRDependency() {
        val dependencyNames = DefaultDeviceCommandHandler::class.java.declaredFields
            .map { it.type.name.lowercase() }

        assertTrue(dependencyNames.isNotEmpty())
        assertFalse(dependencyNames.any { "signalr" in it || "hubconnection" in it })
    }

    @Test
    fun domainFallbackControllerExplicitlyReportsItsActivityScope() {
        val controller = DefaultMonitoringServiceController(DeviceClock { 1L })

        assertEquals(MonitoringLifecycle.ACTIVITY_FOREGROUND, controller.lifecycle)
    }
}
