package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import java.time.Instant

class AndroidRegistrationRequestMapper : DeviceRegistrationRequestMapper {
    override fun map(
        identity: DeviceIdentity,
        capabilities: DeviceCapabilities,
        nowMillis: Long,
    ) = DeviceRegistrationRequest(
        displayName = identity.friendlyName,
        identity = RegistrationIdentityRequest(
            installationId = identity.deviceId,
            manufacturer = identity.manufacturer,
            model = identity.deviceModel,
            platform = "Android",
            operatingSystemVersion = identity.androidVersion,
            appVersion = identity.appVersion,
            appBuild = identity.appBuild,
            apiLevel = identity.apiLevel,
        ),
        capabilities = capabilities.all()
            .filterValues { it == CapabilityAccess.Available }
            .mapNotNull { (capability, _) -> CAPABILITY_NAMES[capability] }
            .sorted()
            .map(::RegistrationCapabilityRequest),
        clientUtcNow = Instant.ofEpochMilli(nowMillis).toString(),
    )

    private companion object {
        val CAPABILITY_NAMES = mapOf(
            AppCapability.MANUAL_RECORDING to "manual-recording",
            AppCapability.RECORDING_LIBRARY to "recording-library",
            AppCapability.RECORDING_PROFILES to "recording-profiles",
            AppCapability.SEGMENTED_RECORDING to "segmented-recording",
            AppCapability.STORAGE_MONITORING to "storage-monitoring",
            AppCapability.BASIC_MOTION_DETECTION to "motion-detection",
            AppCapability.ADVANCED_MOTION_SENSITIVITY to "advanced-motion-sensitivity",
            AppCapability.MONITORING_SERVICE to "monitoring-service",
            AppCapability.MULTIPLE_CAMERAS to "multiple-cameras",
            AppCapability.AUDIO_RECORDING to "audio-recording",
            AppCapability.FLASH to "camera-flash",
            AppCapability.NOTIFICATION_ACTIONS to "notification-actions",
        )
    }
}
