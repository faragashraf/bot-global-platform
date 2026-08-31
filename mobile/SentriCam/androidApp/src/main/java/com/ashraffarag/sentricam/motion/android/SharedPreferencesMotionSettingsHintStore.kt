package com.ashraffarag.sentricam.motion.android

import android.content.Context
import androidx.core.content.edit
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsHintStore

class SharedPreferencesMotionSettingsHintStore(context: Context) : MotionSettingsHintStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override var hasShownMotionSettingsHint: Boolean
        get() = preferences.getBoolean(KEY_HINT_SHOWN, false)
        set(value) = preferences.edit { putBoolean(KEY_HINT_SHOWN, value) }

    private companion object {
        const val PREFERENCES_NAME = "motion_detection_ux"
        const val KEY_HINT_SHOWN = "settings_hint_shown"
    }
}
