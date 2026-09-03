package com.botglobal.mobile.platform.preferences

/**
 * Minimal product-neutral storage for non-sensitive application preferences.
 * Credentials and authenticated sessions belong in a secure vault instead.
 */
interface PreferenceStore {
    fun string(key: String): String?

    fun putString(key: String, value: String)

    fun boolean(key: String): Boolean?

    fun putBoolean(key: String, value: Boolean)
}

class InMemoryPreferenceStore(
    initialValues: Map<String, String> = emptyMap(),
    initialBooleanValues: Map<String, Boolean> = emptyMap(),
) : PreferenceStore {
    private val stringValues = initialValues.toMutableMap()
    private val booleanValues = initialBooleanValues.toMutableMap()

    override fun string(key: String): String? = stringValues[key]

    override fun putString(key: String, value: String) {
        stringValues[key] = value
    }

    override fun boolean(key: String): Boolean? = booleanValues[key]

    override fun putBoolean(key: String, value: Boolean) {
        booleanValues[key] = value
    }
}
