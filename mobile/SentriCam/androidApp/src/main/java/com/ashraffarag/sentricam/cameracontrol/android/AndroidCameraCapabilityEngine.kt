package com.ashraffarag.sentricam.cameracontrol.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSettingsRepository
import com.ashraffarag.sentricam.cameracontrol.capability.CameraCapabilityReporter
import com.ashraffarag.sentricam.cameracontrol.domain.CameraCapabilityDescriptor
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlIds
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlTelemetry
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlRecordingUpload
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import java.time.Instant
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem
import com.ashraffarag.sentricam.motion.capability.MotionSettingsCapabilityReporter

/** Reports hardware truth and clearly marks CameraX read-only gaps instead of inventing support. */
class AndroidCameraCapabilityEngine(
    context: Context,
    private val settings: CameraControlSettingsRepository,
    private val snapshot: () -> DeviceSnapshot,
    private val streaming: () -> Boolean,
    private val latestUpload: () -> RecordingUploadItem? = { null },
    private val motionSettings: MotionSettingsCapabilityReporter,
) : CameraCapabilityReporter {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(CameraManager::class.java)

    override fun report(deviceId: String): CameraControlDeviceReport {
        val current = settings.load()
        val all = cameras.cameraIdList.mapNotNull { id ->
            runCatching { id to cameras.getCameraCharacteristics(id) }.getOrNull()
        }
        val selected = all.firstOrNull { (_, characteristics) ->
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            current.lens == CameraControlValues.FRONT && facing == CameraCharacteristics.LENS_FACING_FRONT
                || current.lens == CameraControlValues.BACK && facing == CameraCharacteristics.LENS_FACING_BACK
        }?.second ?: all.firstOrNull()?.second
        val cameraAvailable = selected != null
            && ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val device = snapshot()
        val cameraReady = cameraAvailable && device.camera.state == DeviceCameraState.READY
        val capabilities = descriptors(current, all.map { it.second }, selected, cameraAvailable, cameraReady, device)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.div(10.0)
        return CameraControlDeviceReport(
            deviceId = deviceId,
            settings = current,
            capabilities = capabilities,
            telemetry = CameraControlTelemetry(
                cameraOnline = cameraAvailable,
                streaming = streaming(),
                recording = device.recordingState == DeviceRecordingStatus.RECORDING,
                motionArmed = device.motionEnabled,
                batteryPercent = device.battery.levelPercent,
                temperatureCelsius = temperature,
                availableStorageBytes = device.storage.availableBytes,
                charging = device.battery.isCharging,
                connectionQuality = device.network.quality(),
                previewAvailable = cameraAvailable,
                audioAvailable = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                    && ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                recordingState = device.recordingState.toCameraControlState(),
                recordingOrigin = device.recordingOrigin.name.lowercase(),
                latestRecordingUpload = latestUpload().toCameraControlUpload(),
            ),
            reportedAtUtc = Instant.now().toString(),
            motionSettings = motionSettings.report(),
        )
    }

    private fun descriptors(
        current: com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings,
        all: List<CameraCharacteristics>,
        selected: CameraCharacteristics?,
        cameraAvailable: Boolean,
        cameraReady: Boolean,
        device: DeviceSnapshot,
    ): List<CameraCapabilityDescriptor> {
        val lensValues = buildList {
            if (all.any { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }) add(CameraControlValues.BACK)
            if (all.any { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }) add(CameraControlValues.FRONT)
        }
        val zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selected?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val zoomMin = zoomRatio?.lower?.toDouble() ?: 1.0
        val zoomMax = zoomRatio?.upper?.toDouble()
            ?: selected?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble()
            ?: 1.0
        val exposure = selected?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val fps = selected?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty().map { it.upper }.filter { it in 1..120 }.distinct().sorted()
        val resolutions = selected?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            .orEmpty()
            .filter { it.width >= 320 && it.height >= 240 }
            .sortedByDescending { it.width.toLong() * it.height }
            .map { "${it.width}x${it.height}" }
            .distinct()
            .take(20)
        val flash = selected?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val focusModes = selected?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toList().orEmpty().map(::focusName).distinct()
        val whiteBalance = selected?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toList().orEmpty().map(::whiteBalanceName).distinct()
        val stabilization = selected?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.any { it != 0 } == true
        val scenes = selected?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()
        val exposureLock = selected?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val manualFocus = (selected?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        val encoderBitrate = videoEncoderBitrateRange()
        val encodedVideoAvailable = cameraAvailable && encoderBitrate != null
        return listOf(
            descriptor("frontCamera", lensValues.contains(CameraControlValues.FRONT), false, bool(lensValues.contains(CameraControlValues.FRONT))),
            descriptor("backCamera", lensValues.contains(CameraControlValues.BACK), false, bool(lensValues.contains(CameraControlValues.BACK))),
            descriptor(CameraControlIds.LENS, lensValues.isNotEmpty(), lensValues.size > 1, text(current.lens), allowed = lensValues),
            descriptor(CameraControlIds.TORCH, flash, flash, bool(current.torch)),
            descriptor("flash", flash, false, bool(false), reason = "Still-image flash is not part of the continuous CameraX pipeline."),
            descriptor(CameraControlIds.ZOOM, zoomMax > zoomMin, zoomMax > zoomMin, number(current.zoom), zoomMin, zoomMax, .1, unit = "ratio"),
            descriptor(CameraControlIds.EXPOSURE, exposure != null && exposure.lower != exposure.upper, exposure != null && exposure.lower != exposure.upper, number(current.exposureCompensation.toDouble()), exposure?.lower?.toDouble(), exposure?.upper?.toDouble(), 1.0, unit = "index"),
            descriptor("exposureLock", exposureLock, false, bool(false), reason = "CameraX does not expose a portable exposure-lock control."),
            descriptor("focusModes", focusModes.isNotEmpty(), false, text(focusModes.firstOrNull()), allowed = focusModes),
            descriptor("autoFocus", focusModes.any { it != "off" }, false, bool(focusModes.any { it != "off" })),
            descriptor("manualFocus", manualFocus, false, bool(false), reason = "Manual focus distance is not exposed by the stable CameraX control API."),
            descriptor("hdr", scenes.contains(CameraCharacteristics.CONTROL_SCENE_MODE_HDR), false, bool(false), reason = "HDR scene selection is read-only in CameraX V1."),
            descriptor("nightMode", scenes.contains(CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT), false, bool(false), reason = "The Night profile uses portable CameraX controls instead of OEM scene modes."),
            descriptor("lowLightBoost", false, false, bool(false), reason = "Low-light boost requires newer OEM-specific camera support and is reported read-only."),
            descriptor("whiteBalance", whiteBalance.isNotEmpty(), false, text("auto"), allowed = whiteBalance, reason = "White balance is reported but not writable through stable CameraX controls."),
            descriptor("imageStabilization", stabilization, false, bool(stabilization)),
            descriptor(CameraControlIds.FPS, fps.isNotEmpty(), fps.isNotEmpty(), number(current.framesPerSecond.toDouble()), fps.minOrNull()?.toDouble(), fps.maxOrNull()?.toDouble(), 1.0, allowed = fps.map(Int::toString), unit = "fps"),
            descriptor(CameraControlIds.RESOLUTION, resolutions.isNotEmpty(), resolutions.isNotEmpty(), text(current.resolution), allowed = resolutions),
            descriptor(CameraControlIds.BITRATE, encodedVideoAvailable, encodedVideoAvailable, number(current.bitrate.toDouble()), encoderBitrate?.first, encoderBitrate?.second, 50_000.0, unit = "bps"),
            descriptor(CameraControlIds.QUALITY, encodedVideoAvailable && resolutions.isNotEmpty(), encodedVideoAvailable && resolutions.isNotEmpty(), text(current.quality), allowed = listOf(CameraControlValues.AUTO, CameraControlValues.LOW, CameraControlValues.MEDIUM, CameraControlValues.HIGH)),
            descriptor(CameraControlIds.NIGHT_PROFILE, cameraAvailable, cameraAvailable, text(current.nightProfile), allowed = listOf(CameraControlValues.AUTO, CameraControlValues.DAY, CameraControlValues.NIGHT, CameraControlValues.INDOOR, CameraControlValues.OUTDOOR)),
            descriptor(
                CameraControlIds.DATE_TIME_OVERLAY,
                cameraAvailable,
                cameraAvailable,
                CameraControlValue(dateTimeOverlay = current.dateTimeOverlay),
            ),
            descriptor(CameraControlIds.PREVIEW, cameraAvailable, cameraAvailable, text(current.preview), allowed = listOf(CameraControlValues.VISIBLE, CameraControlValues.HIDDEN, CameraControlValues.DIMMED)),
            descriptor(
                CameraControlIds.RECORDING,
                cameraAvailable,
                cameraReady,
                text(device.recordingState.toCameraControlState()),
                allowed = listOf(CameraControlValues.START, CameraControlValues.STOP),
                reason = if (cameraReady) null else "The shared camera recording engine is not ready.",
            ),
            descriptor("battery", true, false, number(snapshot().battery.levelPercent?.toDouble()), 0.0, 100.0, 1.0, unit = "percent"),
            descriptor("temperature", temperatureValue(), false, number(batteryTemperature()), unit = "celsius"),
            descriptor("charging", true, false, bool(snapshot().battery.isCharging)),
            descriptor("storage", true, false, number(snapshot().storage.availableBytes?.toDouble()), 0.0, snapshot().storage.totalBytes?.toDouble(), unit = "bytes"),
            descriptor("audio", appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE), false, bool(ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)),
        )
    }

    private fun descriptor(
        id: String,
        supported: Boolean,
        writable: Boolean,
        current: CameraControlValue?,
        minimum: Double? = null,
        maximum: Double? = null,
        step: Double? = null,
        allowed: List<String>? = null,
        unit: String? = null,
        reason: String? = null,
    ) = CameraCapabilityDescriptor(id, supported, writable, current, minimum, maximum, step, allowed, unit, reason)

    private fun temperatureValue() = batteryTemperature() != null
    private fun batteryTemperature(): Double? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeUnless { it == Int.MIN_VALUE }
        ?.div(10.0)

    private fun videoEncoderBitrateRange(): Pair<Double, Double>? = runCatching {
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        } ?: return@runCatching null
        val range = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .videoCapabilities
            ?.bitrateRange
            ?: return@runCatching null
        range.lower.toDouble() to range.upper.toDouble()
    }.getOrNull()

    private fun ConnectivityState.quality() = when (this) {
        ConnectivityState.Offline -> "offline"
        is ConnectivityState.CaptivePortal -> "limited"
        is ConnectivityState.Limited -> "limited"
        is ConnectivityState.LocalNetwork -> "good"
        is ConnectivityState.InternetAvailable -> if (metered) "fair" else "good"
    }

    private fun focusName(value: Int) = when (value) {
        0 -> "off"
        1 -> "auto"
        2 -> "macro"
        3 -> "continuous-video"
        4 -> "continuous-picture"
        5 -> "edof"
        else -> "mode-$value"
    }

    private fun whiteBalanceName(value: Int) = when (value) {
        0 -> "off"
        1 -> "auto"
        2 -> "incandescent"
        3 -> "fluorescent"
        4 -> "warm-fluorescent"
        5 -> "daylight"
        6 -> "cloudy"
        7 -> "twilight"
        8 -> "shade"
        else -> "mode-$value"
    }

    private fun bool(value: Boolean?) = CameraControlValue(boolean = value)
    private fun number(value: Double?) = CameraControlValue(number = value)
    private fun text(value: String?) = CameraControlValue(text = value)

    private fun DeviceRecordingStatus.toCameraControlState(): String = when (this) {
        DeviceRecordingStatus.PREPARING,
        DeviceRecordingStatus.READY,
        DeviceRecordingStatus.STARTING,
        -> CameraControlValues.STARTING
        DeviceRecordingStatus.RECORDING,
        DeviceRecordingStatus.ROTATING_SEGMENT,
        -> CameraControlValues.RECORDING
        DeviceRecordingStatus.STOPPING -> CameraControlValues.STOPPING
        DeviceRecordingStatus.FAILED -> CameraControlValues.FAILED
        DeviceRecordingStatus.IDLE,
        DeviceRecordingStatus.COMPLETED,
        -> CameraControlValues.IDLE
    }

    private fun RecordingUploadItem?.toCameraControlUpload(): CameraControlRecordingUpload? = this?.let { item ->
        CameraControlRecordingUpload(
            clientRecordingId = item.clientRecordingId,
            state = item.state.name.lowercase(),
            progressPercent = item.progressPercent,
            lastErrorCode = item.lastErrorCode,
            serverRecordingId = item.serverRecordingId,
            recordedAtUtc = Instant.ofEpochMilli(item.createdAtMillis).toString(),
            uploadedAtUtc = item.uploadedAtMillis?.let { Instant.ofEpochMilli(it).toString() },
        )
    }
}
