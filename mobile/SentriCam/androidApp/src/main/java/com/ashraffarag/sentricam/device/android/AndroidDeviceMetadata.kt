package com.ashraffarag.sentricam.device.android

import android.os.Build
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import com.ashraffarag.sentricam.device.domain.DevicePlatformMetadata

object AndroidDeviceMetadata {
    fun current(): DevicePlatformMetadata {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val displayModel = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Android device" }
        return DevicePlatformMetadata(
            platform = DevicePlatform.ANDROID,
            defaultFriendlyName = displayModel,
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = model.ifBlank { displayModel },
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            buildFingerprint = Build.FINGERPRINT.takeUnless { it.isNullOrBlank() },
            manufacturer = manufacturer.ifBlank { "Android" },
            appBuild = BuildConfig.VERSION_CODE.toString(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }
}
