package com.ashraffarag.sentricam.device.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOperatingModeResolverTest {

    @Test
    fun `missing persisted hub identity resolves to standalone`() {
        assertEquals(
            CameraOperatingMode.Standalone,
            CameraOperatingModeResolver.resolve(serverDeviceId = null),
        )
    }

    @Test
    fun `blank persisted hub identity resolves to standalone`() {
        assertEquals(
            CameraOperatingMode.Standalone,
            CameraOperatingModeResolver.resolve(serverDeviceId = "   "),
        )
    }

    @Test
    fun `persisted hub identity resolves to hub managed`() {
        assertEquals(
            CameraOperatingMode.HubManaged(
                serverDeviceId = SERVER_DEVICE_ID,
            ),
            CameraOperatingModeResolver.resolve(
                serverDeviceId = SERVER_DEVICE_ID,
            ),
        )
    }

    @Test
    fun `persisted hub identity is normalized before resolving`() {
        assertEquals(
            CameraOperatingMode.HubManaged(
                serverDeviceId = SERVER_DEVICE_ID,
            ),
            CameraOperatingModeResolver.resolve(
                serverDeviceId = "  $SERVER_DEVICE_ID  ",
            ),
        )
    }

    private companion object {
        const val SERVER_DEVICE_ID =
            "58aa38f4-e9e5-4ed3-9ecf-488479efff43"
    }
}
