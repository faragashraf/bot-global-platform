package com.ashraffarag.sentricam.device.android

import android.content.Context
import android.content.pm.PackageManager
import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.capability.domain.EntitlementService
import com.ashraffarag.sentricam.device.domain.DeviceCapabilities

class AndroidDeviceCapabilitiesResolver(
    context: Context,
    private val entitlements: EntitlementService,
) {
    private val packageManager = context.applicationContext.packageManager

    fun resolve(): DeviceCapabilities = DeviceCapabilities.of(
        AppCapability.MANUAL_RECORDING to access(AppCapability.MANUAL_RECORDING, cameraAccess()),
        AppCapability.RECORDING_LIBRARY to access(AppCapability.RECORDING_LIBRARY),
        AppCapability.RECORDING_PROFILES to access(AppCapability.RECORDING_PROFILES, cameraAccess()),
        AppCapability.SEGMENTED_RECORDING to access(AppCapability.SEGMENTED_RECORDING, cameraAccess()),
        AppCapability.STORAGE_MONITORING to access(AppCapability.STORAGE_MONITORING),
        AppCapability.BASIC_MOTION_DETECTION to access(AppCapability.BASIC_MOTION_DETECTION, cameraAccess()),
        AppCapability.ADVANCED_MOTION_SENSITIVITY to access(
            AppCapability.ADVANCED_MOTION_SENSITIVITY,
            cameraAccess(),
        ),
        AppCapability.SMART_PERSON_DETECTION to access(AppCapability.SMART_PERSON_DETECTION),
        AppCapability.ANIMAL_DETECTION to access(AppCapability.ANIMAL_DETECTION),
        AppCapability.VEHICLE_DETECTION to access(AppCapability.VEHICLE_DETECTION),
        AppCapability.KNOWN_PERSON_RECOGNITION to access(AppCapability.KNOWN_PERSON_RECOGNITION),
        AppCapability.ADVANCED_DETECTION_RULES to access(AppCapability.ADVANCED_DETECTION_RULES),
        AppCapability.MONITORING_SERVICE to access(AppCapability.MONITORING_SERVICE, cameraAccess()),
        AppCapability.REMOTE_CONTROL to access(AppCapability.REMOTE_CONTROL),
        AppCapability.MULTIPLE_DEVICES to access(AppCapability.MULTIPLE_DEVICES),
        AppCapability.MULTIPLE_CAMERAS to access(AppCapability.MULTIPLE_CAMERAS, if (hasFrontAndRearCamera()) {
            CapabilityAccess.Available
        } else {
            CapabilityAccess.UnsupportedOnDevice("single_camera")
        }),
        AppCapability.CLOUD_UPLOAD to access(AppCapability.CLOUD_UPLOAD),
        AppCapability.EXTENDED_RECORDING_HISTORY to access(AppCapability.EXTENDED_RECORDING_HISTORY),
        AppCapability.AUDIO_RECORDING to access(AppCapability.AUDIO_RECORDING, if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        ) {
            CapabilityAccess.Available
        } else {
            CapabilityAccess.UnsupportedOnDevice("no_microphone")
        }),
        AppCapability.FLASH to access(AppCapability.FLASH, if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        ) {
            CapabilityAccess.Available
        } else {
            CapabilityAccess.UnsupportedOnDevice("no_flash")
        }),
        AppCapability.NOTIFICATION_ACTIONS to access(AppCapability.NOTIFICATION_ACTIONS),
    )

    private fun access(
        capability: AppCapability,
        hardwareAccess: CapabilityAccess = CapabilityAccess.Available,
    ): CapabilityAccess = when (val entitlement = entitlements.getAccess(capability)) {
        CapabilityAccess.Available -> hardwareAccess
        else -> entitlement
    }

    private fun cameraAccess(): CapabilityAccess =
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            CapabilityAccess.Available
        } else {
            CapabilityAccess.UnsupportedOnDevice("no_camera")
        }

    private fun hasFrontAndRearCamera(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}
