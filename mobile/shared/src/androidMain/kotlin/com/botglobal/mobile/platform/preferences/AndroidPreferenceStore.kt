package com.botglobal.mobile.platform.preferences

import android.content.Context

class AndroidPreferenceStore(
    context: Context,
    fileName: String,
) : PreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        fileName.requireStorageIdentifier("preference file"),
        Context.MODE_PRIVATE,
    )

    override fun string(key: String): String? =
        preferences.getString(key.requireStorageIdentifier("preference key"), null)

    override fun putString(key: String, value: String) {
        check(
            preferences.edit()
                .putString(key.requireStorageIdentifier("preference key"), value)
                .commit(),
        ) {
            "Unable to persist application preference."
        }
    }
}

private fun String.requireStorageIdentifier(label: String): String =
    trim().takeIf { value ->
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "._-" }
    } ?: error("A valid $label is required.")
