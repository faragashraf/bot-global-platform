package com.ashraffarag.sentricam.device.domain

import java.util.UUID

enum class DevicePlatform {
    ANDROID,
}

data class DeviceIdentity(
    val deviceId: String,
    val friendlyName: String,
    val platform: DevicePlatform,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val buildFingerprint: String?,
    val installedAtMillis: Long,
    val lastStartupAtMillis: Long,
    val manufacturer: String = "Android",
    val appBuild: String = "0",
    val apiLevel: Int = 23,
)

data class DevicePlatformMetadata(
    val platform: DevicePlatform,
    val defaultFriendlyName: String,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val buildFingerprint: String?,
    val manufacturer: String = "Android",
    val appBuild: String = "0",
    val apiLevel: Int = 23,
)

data class StoredDeviceIdentity(
    val deviceId: String,
    val friendlyName: String?,
    val installedAtMillis: Long?,
    val lastStartupAtMillis: Long? = null,
    val schemaVersion: Int = 0,
)

interface DeviceIdentityStore {
    fun load(): StoredDeviceIdentity?
    fun save(identity: StoredDeviceIdentity)
}

fun interface DeviceIdGenerator {
    fun generate(): String
}

fun interface DeviceClock {
    fun nowMillis(): Long
}

class DefaultDeviceIdentityRepository(
    private val store: DeviceIdentityStore,
    private val metadata: DevicePlatformMetadata,
    private val clock: DeviceClock,
    private val idGenerator: DeviceIdGenerator = DeviceIdGenerator { UUID.randomUUID().toString() },
) {
    private var current: DeviceIdentity? = null

    fun start(): DeviceIdentity {
        val now = clock.nowMillis()
        val stored = store.load()
        val identity = DeviceIdentity(
            deviceId = stored?.deviceId?.takeIf(::isUuid) ?: idGenerator.generate(),
            friendlyName = stored?.friendlyName?.takeIf { it.isNotBlank() }
                ?: metadata.defaultFriendlyName,
            platform = metadata.platform,
            appVersion = metadata.appVersion,
            deviceModel = metadata.deviceModel,
            androidVersion = metadata.androidVersion,
            buildFingerprint = metadata.buildFingerprint,
            installedAtMillis = stored?.installedAtMillis?.takeIf { it > 0L } ?: now,
            lastStartupAtMillis = now,
            manufacturer = metadata.manufacturer,
            appBuild = metadata.appBuild,
            apiLevel = metadata.apiLevel,
        )
        current = identity
        persist(identity)
        return identity
    }

    fun rename(friendlyName: String): DeviceIdentity {
        val normalized = friendlyName.trim().take(MAX_FRIENDLY_NAME_LENGTH)
        require(normalized.isNotEmpty()) { "Friendly name cannot be blank" }
        val updated = checkNotNull(current) { "Identity repository has not been started" }
            .copy(friendlyName = normalized)
        current = updated
        persist(updated)
        return updated
    }

    private fun persist(identity: DeviceIdentity) {
        store.save(
            StoredDeviceIdentity(
                deviceId = identity.deviceId,
                friendlyName = identity.friendlyName,
                installedAtMillis = identity.installedAtMillis,
                lastStartupAtMillis = identity.lastStartupAtMillis,
                schemaVersion = CURRENT_SCHEMA_VERSION,
            ),
        )
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_FRIENDLY_NAME_LENGTH = 64
    }
}
