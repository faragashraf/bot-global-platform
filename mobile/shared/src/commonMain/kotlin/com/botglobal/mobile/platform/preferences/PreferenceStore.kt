package com.botglobal.mobile.platform.preferences

/**
 * Minimal product-neutral storage for non-sensitive application preferences.
 * Credentials and authenticated sessions belong in a secure vault instead.
 */
interface PreferenceStore {
    fun string(key: String): String?

    fun putString(key: String, value: String)
}

class InMemoryPreferenceStore(
    initialValues: Map<String, String> = emptyMap(),
) : PreferenceStore {
    private val values = initialValues.toMutableMap()

    override fun string(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
