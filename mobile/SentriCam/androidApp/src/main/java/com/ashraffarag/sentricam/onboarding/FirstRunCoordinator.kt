package com.ashraffarag.sentricam.onboarding

import android.content.Context
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettingsRepository

data class FirstRunState(
    val firstLaunchDetected: Boolean = false,
    val firstPairingCompleted: Boolean = false,
    val firstHubConnectionCompleted: Boolean = false,
    val monitoringAutoEnabled: Boolean = false,
    val actionRequiredCode: String? = null,
)

interface FirstRunStateStore {
    fun load(): FirstRunState
    fun save(state: FirstRunState)
}

class SharedPreferencesFirstRunStateStore(context: Context) : FirstRunStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load() = FirstRunState(
        firstLaunchDetected = preferences.getBoolean(KEY_FIRST_LAUNCH, false),
        firstPairingCompleted = preferences.getBoolean(KEY_FIRST_PAIRING, false),
        firstHubConnectionCompleted = preferences.getBoolean(KEY_FIRST_CONNECTION, false),
        monitoringAutoEnabled = preferences.getBoolean(KEY_MONITORING_AUTO_ENABLED, false),
        actionRequiredCode = preferences.getString(KEY_ACTION_REQUIRED, null),
    )

    override fun save(state: FirstRunState) {
        preferences.edit()
            .putBoolean(KEY_FIRST_LAUNCH, state.firstLaunchDetected)
            .putBoolean(KEY_FIRST_PAIRING, state.firstPairingCompleted)
            .putBoolean(KEY_FIRST_CONNECTION, state.firstHubConnectionCompleted)
            .putBoolean(KEY_MONITORING_AUTO_ENABLED, state.monitoringAutoEnabled)
            .putString(KEY_ACTION_REQUIRED, state.actionRequiredCode)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "first_run_state"
        const val KEY_FIRST_LAUNCH = "first_launch_detected"
        const val KEY_FIRST_PAIRING = "first_pairing_completed"
        const val KEY_FIRST_CONNECTION = "first_hub_connection_completed"
        const val KEY_MONITORING_AUTO_ENABLED = "monitoring_auto_enabled"
        const val KEY_ACTION_REQUIRED = "action_required"
    }
}

class FirstRunCoordinator(
    private val stateStore: FirstRunStateStore,
    private val monitoringSettings: MonitoringSettingsRepository,
) {
    fun state(): FirstRunState = stateStore.load()

    fun detectLaunch(existingRegistration: Boolean): FirstRunState = update { current ->
        current.copy(
            firstLaunchDetected = true,
            // An upgraded installation with an existing credential is not a new pairing and
            // must not unexpectedly change the operator's monitoring preference.
            firstPairingCompleted = current.firstPairingCompleted || existingRegistration,
        )
    }

    fun pairingSucceeded(cameraPermissionGranted: Boolean): FirstRunState {
        val currentSettings = monitoringSettings.load()
        monitoringSettings.save(
            currentSettings.copy(
                enabled = true,
                autoStart = true,
                restartOnFailure = true,
            ),
        )
        return update {
            it.copy(
                firstPairingCompleted = true,
                monitoringAutoEnabled = true,
                actionRequiredCode = if (cameraPermissionGranted) null else ACTION_CAMERA_PERMISSION,
            )
        }
    }

    fun hubConnected(): FirstRunState = update {
        it.copy(firstHubConnectionCompleted = true)
    }

    fun pairingRemoved(): FirstRunState = update {
        it.copy(
            firstPairingCompleted = false,
            firstHubConnectionCompleted = false,
            monitoringAutoEnabled = false,
            actionRequiredCode = null,
        )
    }

    fun monitoringStarted(): FirstRunState = update {
        it.copy(actionRequiredCode = null)
    }

    fun monitoringStartFailed(code: String): FirstRunState = update {
        it.copy(actionRequiredCode = code)
    }

    private fun update(transform: (FirstRunState) -> FirstRunState): FirstRunState {
        val next = transform(stateStore.load())
        stateStore.save(next)
        return next
    }

    companion object {
        const val ACTION_CAMERA_PERMISSION = "camera_permission_required"
        const val ACTION_MONITORING_START = "monitoring_start_failed"
    }
}
