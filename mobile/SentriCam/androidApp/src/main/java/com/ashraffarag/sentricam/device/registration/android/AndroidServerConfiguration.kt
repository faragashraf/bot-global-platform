package com.ashraffarag.sentricam.device.registration.android

import android.content.Context
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.device.registration.ServerConfiguration

class AndroidServerConfiguration(context: Context) : ServerConfiguration {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override val isDebug: Boolean = BuildConfig.DEBUG

    override fun baseUrl(): String =
        preferences.getString(KEY_PAIRED_BASE_URL, null)
            ?: if (isDebug) {
                preferences.getString(KEY_DEBUG_BASE_URL, null) ?: BuildConfig.DEFAULT_SERVER_BASE_URL
            } else {
                BuildConfig.DEFAULT_SERVER_BASE_URL
            }

    override fun updateBaseUrl(normalizedUrl: String): Boolean {
        return preferences.edit()
            .putString(if (isDebug) KEY_DEBUG_BASE_URL else KEY_PAIRED_BASE_URL, normalizedUrl)
            .commit()
    }

    private companion object {
        const val PREFERENCES = "server_configuration"
        const val KEY_DEBUG_BASE_URL = "debug_server_base_url"
        const val KEY_PAIRED_BASE_URL = "paired_hub_base_url"
    }
}
