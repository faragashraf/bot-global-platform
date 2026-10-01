# Gson serializes these application models by reflecting on their Kotlin backing fields. Preserve
# the JSON field names while still allowing R8 to optimize the classes and their implementations.
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.RegistrationIdentityRequest { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.RegistrationCapabilityRequest { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.DeviceRegistrationRequest { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.HubPairingCompletionRequest { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.DeviceRegistrationResponse { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.RegistrationDetails { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.registration.DeviceCredentials { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.time.HubTimeResponse { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.recovery.SubsystemHealthReport { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.recovery.OperationalHealthSnapshot { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.recording.upload.android.RecordingUploadApiClient$UploadResponse { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.recording.upload.android.RecordingUploadApiClient$ServerRecording { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.recording.upload.android.SharedPreferencesRecordingUploadQueue$QueuePayload { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem { <fields>; }

# Microsoft SignalR's JSON hub protocol reflects over its protocol messages and the application
# payload types passed to HubConnection.on/invoke.
-keepclasseswithmembers class com.microsoft.signalr.*Message { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.DevicePairingRevoked { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.SignalRHeartbeat { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.SignalRHeartbeatAcknowledgement { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.DeviceOperationalHealthReport { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.RemoteDeviceCommand { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.communication.signalr.RemoteDeviceCommandResult { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveSessionView { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveSessionAssignment { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveSessionDescription { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveIceCandidate { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveSessionStatistics { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LivePreviewVisibility { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveCameraCapability { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraCapabilityDescriptor { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlTelemetry { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlRecordingUpload { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCancellation { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.motion.capability.MotionSettingValue { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.motion.capability.MotionSettingDescriptor { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.motion.capability.MotionSettingsValues { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.motion.capability.MotionEffectiveConfiguration { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.motion.capability.MotionSettingsDeviceReport { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration { <fields>; }

# Heartbeats and command results contain the full device snapshot. Retain only the reflected models
# and fields in that object graph; their executable methods remain eligible for R8 optimization.
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.DeviceSnapshot { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.MotionConfigurationSummary { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.CameraState { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.BatteryState { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.StorageState { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.MemorySummary { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.ConnectivityState$* { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.device.domain.DeviceCapabilities { <fields>; }
-keepclasseswithmembers class com.ashraffarag.sentricam.capability.domain.CapabilityAccess$* { <fields>; }

# Gson derives JSON enum values from enum constant names. Preserve names only for enums present in
# persisted or SignalR JSON models.
-keepclassmembers enum com.ashraffarag.sentricam.device.recovery.OperationalSubsystem { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.recovery.OperationalLifecycleState { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.recovery.OperationalHealthState { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadState { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.DevicePlatform { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.RecordingOrigin { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.DeviceMotionStatus { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.motion.domain.MotionSensitivity { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.DeviceCameraState { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.DeviceCameraLens { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.capability.domain.AppCapability { public static final ** *; }
-keepclassmembers enum com.ashraffarag.sentricam.device.domain.FutureFeatureState { public static final ** *; }

# The WebRTC SDK's native library calls Java methods marked with these annotations. The AAR does
# not bundle consumer rules, so retain only annotated JNI entry points and their descriptor types.
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}

# SignalR depends on slf4j-api without a concrete logging binding. SLF4J 1.7 explicitly catches
# this class's absence and selects NOPLoggerFactory, so the missing implementation is intentional.
-dontwarn org.slf4j.impl.StaticLoggerBinder
