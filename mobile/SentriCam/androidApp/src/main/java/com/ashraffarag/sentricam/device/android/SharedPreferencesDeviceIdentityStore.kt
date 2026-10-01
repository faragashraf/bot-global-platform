package com.ashraffarag.sentricam.device.android

import android.content.Context
import com.ashraffarag.sentricam.device.domain.DeviceIdentityStore
import com.ashraffarag.sentricam.device.domain.StoredDeviceIdentity

class SharedPreferencesDeviceIdentityStore(context: Context) : DeviceIdentityStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): StoredDeviceIdentity? {
        val id = preferences.getString(KEY_DEVICE_ID, null)
            ?: preferences.getString(LEGACY_DEVICE_ID, null)
            ?: return null
        return StoredDeviceIdentity(
            deviceId = id,
            friendlyName = preferences.getString(KEY_FRIENDLY_NAME, null)
                ?: preferences.getString(LEGACY_FRIENDLY_NAME, null),
            installedAtMillis = preferences.getLong(KEY_INSTALLED_AT, 0L).takeIf { it > 0L },
            lastStartupAtMillis = preferences.getLong(KEY_LAST_STARTUP_AT, 0L).takeIf { it > 0L },
            schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, 0),
        )
    }

    override fun save(identity: StoredDeviceIdentity) {
        preferences.edit()
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_FRIENDLY_NAME, identity.friendlyName)
            .putLong(KEY_INSTALLED_AT, identity.installedAtMillis ?: 0L)
            .putLong(KEY_LAST_STARTUP_AT, identity.lastStartupAtMillis ?: 0L)
            .putInt(KEY_SCHEMA_VERSION, identity.schemaVersion)
            .remove(LEGACY_DEVICE_ID)
            .remove(LEGACY_FRIENDLY_NAME)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "device_identity"
        const val KEY_DEVICE_ID = "device_uuid"
        const val KEY_FRIENDLY_NAME = "friendly_name"
        const val KEY_INSTALLED_AT = "installed_at_millis"
        const val KEY_LAST_STARTUP_AT = "last_startup_at_millis"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val LEGACY_DEVICE_ID = "device_id"
        const val LEGACY_FRIENDLY_NAME = "device_name"
    }
}
