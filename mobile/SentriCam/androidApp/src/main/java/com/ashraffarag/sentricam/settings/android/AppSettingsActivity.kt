package com.ashraffarag.sentricam.settings.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.SentriCamApplication
import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.communication.signalr.SignalRConnectionMetrics
import com.ashraffarag.sentricam.communication.signalr.SignalRState
import com.ashraffarag.sentricam.databinding.ActivityAppSettingsBinding
import com.ashraffarag.sentricam.databinding.SettingsHomeBinding
import com.ashraffarag.sentricam.databinding.ViewSettingsNavigationRowBinding
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.DeviceMotionStatus
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.registration.RegistrationFailure
import com.ashraffarag.sentricam.device.registration.RegistrationState
import com.ashraffarag.sentricam.monitoring.domain.MonitoringNotificationOptions
import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.pairing.android.QrPairingScannerActivity
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsPolicy
import com.ashraffarag.sentricam.recording.engine.android.AndroidRecordingStorageGateway
import com.ashraffarag.sentricam.recording.engine.android.SharedPreferencesRecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.android.ui.RecordingEngineUiFormatter
import com.ashraffarag.sentricam.recording.engine.capability.RecordingStorageCheck
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineSettingsPolicy
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration
import com.ashraffarag.sentricam.settings.domain.SettingsDestination
import com.ashraffarag.sentricam.settings.domain.SettingsSection
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class AppSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppSettingsBinding
    private lateinit var viewModel: AppSettingsViewModel
    private val deviceRuntime get() = (application as SentriCamApplication).deviceRuntime
    private val entitlements get() = deviceRuntime.entitlements
    private var storageCheck: RecordingStorageCheck? = null
    private var deviceStateJob: Job? = null
    private var registrationStateJob: Job? = null
    private var signalRStateJob: Job? = null
    private var signalRMetricsJob: Job? = null
    private var settingsHomeAvailable = false
    private var showingSettingsHome = false
    private var deviceDetailsExpanded = false

    private val pairingScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = result.data?.getStringExtra(QrPairingScannerActivity.EXTRA_PAIRING_PAYLOAD)
        when {
            result.resultCode == RESULT_OK && !payload.isNullOrBlank() -> completePairing(payload)
            result.data?.getStringExtra(QrPairingScannerActivity.EXTRA_ACTION_REQUIRED) ==
                QrPairingScannerActivity.ACTION_CAMERA_PERMISSION -> Toast.makeText(
                    this,
                    R.string.pairing_scan_camera_failed,
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsHomeAvailable = intent.getBooleanExtra(EXTRA_SHOW_HOME, false)
        deviceDetailsExpanded = savedInstanceState?.getBoolean(STATE_DEVICE_DETAILS) ?: false

        val destination = SettingsDestination(readInitialSection())
        val repository = CompositeAppSettingsRepository(
            SharedPreferencesRecordingSettingsRepository(applicationContext),
            SharedPreferencesRecordingEngineSettingsRepository(applicationContext, BuildConfig.DEBUG),
            deviceRuntime.motionSettings,
            deviceRuntime.monitoringSettings,
        )
        viewModel = ViewModelProvider(
            this,
            AppSettingsViewModel.Factory(repository, destination),
        )[AppSettingsViewModel::class.java]

        binding.settingsToolbar.setNavigationOnClickListener { handleBackNavigation() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackNavigation()
        })
        binding.applySettings.setOnClickListener {
            viewModel.coordinator.apply()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SETTINGS_CHANGED, true))
            finish()
        }
        if (settingsHomeAvailable) renderSettingsHome() else renderSelectedSection()
        loadStorageStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DEVICE_DETAILS, deviceDetailsExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun renderSettingsHome() {
        cancelSectionObservers()
        showingSettingsHome = true
        binding.settingsToolbar.subtitle = null
        binding.settingsContent.removeAllViews()
        val home = SettingsHomeBinding.inflate(layoutInflater, binding.settingsContent, false)
        binding.settingsContent.addView(home.root)
        SETTINGS_HOME_ITEMS.forEach { item ->
            val row = ViewSettingsNavigationRowBinding.inflate(
                layoutInflater,
                home.settingsHomeNavigation,
                false,
            )
            val title = getString(item.title)
            val summary = getString(item.summary)
            row.settingsNavigationIcon.setImageResource(item.icon)
            row.settingsNavigationTitle.text = title
            row.settingsNavigationSummary.text = summary
            row.settingsNavigationTitle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.settingsNavigationSummary.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.root.contentDescription = "$title. $summary"
            row.root.setOnClickListener {
                viewModel.coordinator.select(item.section)
                renderSelectedSection()
            }
            home.settingsHomeNavigation.addView(row.root)
        }
    }

    private fun renderSelectedSection() {
        cancelSectionObservers()
        showingSettingsHome = false
        val section = viewModel.coordinator.state.value.navigation.selectedSection
        binding.settingsToolbar.subtitle = null
        binding.settingsContent.removeAllViews()
        val layout = when (section) {
            SettingsSection.CAMERA -> R.layout.settings_section_camera
            SettingsSection.RECORDING -> R.layout.settings_section_recording
            SettingsSection.MOTION_DETECTION -> R.layout.settings_section_motion
            SettingsSection.MONITORING -> R.layout.settings_section_monitoring
            SettingsSection.SMART_DETECTION -> R.layout.settings_section_smart
            SettingsSection.DEVICE -> R.layout.settings_section_device
            SettingsSection.SYSTEM_CONNECTIVITY -> R.layout.settings_section_system
        }
        val root = LayoutInflater.from(this).inflate(layout, binding.settingsContent, false)
        binding.settingsContent.addView(root)
        root.isFocusableInTouchMode = true
        root.requestFocus()
        when (section) {
            SettingsSection.CAMERA -> bindCamera(root)
            SettingsSection.RECORDING -> bindRecording(root)
            SettingsSection.MOTION_DETECTION -> bindMotion(root)
            SettingsSection.MONITORING -> bindMonitoring(root)
            SettingsSection.SMART_DETECTION -> bindSmartDetection(root)
            SettingsSection.DEVICE -> bindDevice(root)
            SettingsSection.SYSTEM_CONNECTIVITY -> bindSystem(root)
        }
    }

    private fun cancelSectionObservers() {
        deviceStateJob?.cancel()
        registrationStateJob?.cancel()
        signalRStateJob?.cancel()
        signalRMetricsJob?.cancel()
    }

    private fun handleBackNavigation() {
        if (settingsHomeAvailable && !showingSettingsHome) {
            renderSettingsHome()
        } else {
            finish()
        }
    }

    private fun bindDevice(root: View) {
        val name = root.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.settings_device_friendly_name,
        )
        name.setText(deviceRuntime.device.state.value.identity.friendlyName)
        name.doAfterTextChanged { editable ->
            val updated = editable?.toString()?.trim().orEmpty()
            if (updated.isNotEmpty() && updated != deviceRuntime.device.state.value.identity.friendlyName) {
                deviceRuntime.renameDevice(updated)
            }
        }
        root.findViewById<MaterialButton>(R.id.settings_device_details_toggle).setOnClickListener {
            deviceDetailsExpanded = !deviceDetailsExpanded
            renderDeviceDetails(root)
        }
        renderDeviceDetails(root)
        observeSnapshot(root, ::renderDevice)
    }

    private fun bindMonitoring(root: View) {
        val settings = viewModel.coordinator.state.value.draft.monitoring
        root.findViewById<View>(R.id.settings_monitoring_debug_group).visibility =
            if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        root.findViewById<MaterialSwitch>(R.id.settings_monitoring_enabled).bind(settings.enabled) { enabled ->
            updateMonitoring { copy(enabled = enabled) }
        }
        root.findViewById<MaterialSwitch>(R.id.settings_monitoring_auto_start).bind(settings.autoStart) { enabled ->
            updateMonitoring { copy(autoStart = enabled) }
        }
        root.findViewById<MaterialSwitch>(R.id.settings_notification_motion).bind(
            settings.notification.showMotionStatus,
        ) { enabled -> updateNotification { copy(showMotionStatus = enabled) } }
        root.findViewById<MaterialSwitch>(R.id.settings_notification_recording).bind(
            settings.notification.showRecordingStatus,
        ) { enabled -> updateNotification { copy(showRecordingStatus = enabled) } }
        root.findViewById<MaterialSwitch>(R.id.settings_notification_battery).bind(
            settings.notification.showBatteryLevel,
        ) { enabled -> updateNotification { copy(showBatteryLevel = enabled) } }
        root.findViewById<MaterialSwitch>(R.id.settings_notification_storage).bind(
            settings.notification.showStorageWarnings,
        ) { enabled -> updateNotification { copy(showStorageWarnings = enabled) } }
        root.findViewById<MaterialSwitch>(R.id.settings_monitoring_restart_failure).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            bind(settings.restartOnFailure && BuildConfig.DEBUG) { enabled ->
                updateMonitoring { copy(restartOnFailure = enabled && BuildConfig.DEBUG) }
            }
        }
        root.findViewById<MaterialSwitch>(R.id.settings_monitoring_verbose).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            bind(settings.verboseLogging && BuildConfig.DEBUG) { enabled ->
                updateMonitoring { copy(verboseLogging = enabled && BuildConfig.DEBUG) }
            }
        }
    }

    private fun updateMonitoring(transform: com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings.() -> com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings) {
        viewModel.coordinator.updateMonitoring(viewModel.coordinator.state.value.draft.monitoring.transform())
    }

    private fun updateNotification(transform: MonitoringNotificationOptions.() -> MonitoringNotificationOptions) {
        updateMonitoring { copy(notification = notification.transform()) }
    }

    private fun MaterialSwitch.bind(checked: Boolean, onChanged: (Boolean) -> Unit) {
        isChecked = checked
        setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
    }

    private fun renderDevice(root: View, snapshot: DeviceSnapshot) {
        root.findViewById<TextView>(R.id.settings_device_id).text = getString(
            R.string.device_id_format,
            snapshot.deviceId,
        )
        root.findViewById<TextView>(R.id.settings_device_version).text = getString(
            R.string.device_platform_format,
            snapshot.appVersion,
            snapshot.androidVersion,
        )
        root.findViewById<TextView>(R.id.settings_device_battery).apply {
            text = getString(
                R.string.device_battery_format,
                snapshot.battery.levelPercent?.let { "$it%" }
                    ?: getString(R.string.device_value_unavailable),
                when (snapshot.battery.isCharging) {
                    true -> getString(R.string.device_charging)
                    false -> getString(R.string.device_not_charging)
                    null -> getString(R.string.device_value_unavailable)
                },
            )
            applyVisionStatusColor(
                when {
                    snapshot.battery.levelPercent == null -> R.color.vision_status_offline
                    snapshot.battery.levelPercent <= 15 -> R.color.vision_status_critical
                    snapshot.battery.levelPercent <= 30 || snapshot.battery.isPowerSaveMode == true ->
                        R.color.vision_status_motion
                    else -> R.color.vision_status_healthy
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_device_storage).apply {
            text = getString(
                R.string.device_storage_format,
                snapshot.storage.availableBytes?.let(RecordingEngineUiFormatter::storageSize)
                    ?: getString(R.string.device_value_unavailable),
            )
            applyVisionStatusColor(
                if (snapshot.storage.isLow) R.color.vision_status_motion
                else if (snapshot.storage.availableBytes == null) R.color.vision_status_offline
                else R.color.vision_status_healthy,
            )
        }
        root.findViewById<TextView>(R.id.settings_device_camera).apply {
            text = getString(
                R.string.device_camera_format,
                snapshot.camera.state.displayName(),
                snapshot.camera.selectedLens.displayName(),
                snapshot.camera.availableCameraCount,
            )
            applyVisionStatusColor(
                when (snapshot.camera.state) {
                    DeviceCameraState.READY -> R.color.vision_status_healthy
                    DeviceCameraState.STARTING,
                    DeviceCameraState.SWITCHING,
                    -> R.color.vision_status_motion
                    DeviceCameraState.ERROR -> R.color.vision_status_critical
                    DeviceCameraState.UNAVAILABLE -> R.color.vision_status_offline
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_device_monitoring).apply {
            text = getString(
            R.string.device_monitoring_format,
            snapshot.monitoringState.displayName(),
            )
            applyVisionStatusColor(
                when (snapshot.monitoringState) {
                    MonitoringStatus.RUNNING,
                    MonitoringStatus.STARTING,
                    MonitoringStatus.RESTARTING,
                    -> R.color.vision_status_healthy
                    MonitoringStatus.STOPPING -> R.color.vision_status_motion
                    MonitoringStatus.ERROR -> R.color.vision_status_critical
                    MonitoringStatus.STOPPED -> R.color.vision_status_offline
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_device_recording).apply {
            text = getString(
                R.string.device_recording_format,
                "${snapshot.recordingState.displayName()} · ${snapshot.recordingOrigin.displayName()}",
            )
            applyVisionStatusColor(
                when (snapshot.recordingState) {
                    DeviceRecordingStatus.RECORDING -> R.color.vision_status_recording
                    DeviceRecordingStatus.PREPARING,
                    DeviceRecordingStatus.STARTING,
                    DeviceRecordingStatus.ROTATING_SEGMENT,
                    DeviceRecordingStatus.STOPPING,
                    -> R.color.vision_status_motion
                    DeviceRecordingStatus.READY,
                    DeviceRecordingStatus.COMPLETED,
                    -> R.color.vision_status_healthy
                    DeviceRecordingStatus.FAILED -> R.color.vision_status_critical
                    DeviceRecordingStatus.IDLE -> R.color.vision_status_offline
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_device_motion).apply {
            text = getString(R.string.device_motion_format, snapshot.motionState.displayName())
            applyVisionStatusColor(
                when (snapshot.motionState) {
                    DeviceMotionStatus.NO_MOTION -> R.color.vision_status_healthy
                    DeviceMotionStatus.SUSPECTED,
                    DeviceMotionStatus.CONFIRMED,
                    DeviceMotionStatus.HOLDING,
                    DeviceMotionStatus.COOLDOWN,
                    -> R.color.vision_status_motion
                    DeviceMotionStatus.INITIALIZING -> R.color.vision_brand_primary
                    DeviceMotionStatus.ERROR -> R.color.vision_status_critical
                    DeviceMotionStatus.DISABLED -> R.color.vision_status_offline
                },
            )
        }
        val all = snapshot.capabilities.all()
        root.findViewById<TextView>(R.id.settings_device_capabilities).text = getString(
            R.string.device_capabilities_format,
            all.count { it.value == CapabilityAccess.Available },
            all.count { it.value == CapabilityAccess.ComingSoon },
        )
    }

    private fun TextView.applyVisionStatusColor(colorResource: Int) {
        setTextColor(ContextCompat.getColor(context, colorResource))
    }

    private fun renderDeviceDetails(root: View) {
        root.findViewById<View>(R.id.settings_device_technical_details).visibility =
            if (deviceDetailsExpanded) View.VISIBLE else View.GONE
        root.findViewById<MaterialButton>(R.id.settings_device_details_toggle).apply {
            val label = if (deviceDetailsExpanded) {
                R.string.settings_device_hide_details
            } else {
                R.string.settings_device_show_details
            }
            setText(label)
            contentDescription = getString(label)
        }
    }

    private fun bindSystem(root: View) {
        observeSnapshot(root, ::renderSystem)
        val serverUrlContainer = root.findViewById<TextInputLayout>(R.id.settings_server_url_container)
        val serverUrlInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.settings_server_url_input,
        )
        serverUrlContainer.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        serverUrlInput.setText(deviceRuntime.registration.configuredBaseUrl())

        root.findViewById<MaterialButton>(R.id.settings_scan_hub_qr).setOnClickListener {
            pairingScannerLauncher.launch(QrPairingScannerActivity.intent(this))
        }

        root.findViewById<MaterialButton>(R.id.settings_test_connection).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener {
                if (applyServerUrl(serverUrlInput, serverUrlContainer)) {
                    deviceRuntime.registration.testConnection()
                }
            }
        }
        root.findViewById<MaterialButton>(R.id.settings_register_device).setOnClickListener {
            if (applyServerUrl(serverUrlInput, serverUrlContainer)) {
                deviceRuntime.registration.register()
            }
        }
        root.findViewById<MaterialButton>(R.id.settings_forget_registration).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_forget_registration_title)
                .setMessage(R.string.device_forget_registration_message)
                .setNegativeButton(R.string.device_cancel, null)
                .setPositiveButton(R.string.device_forget_registration) { _, _ ->
                    deviceRuntime.remoteConnectivity.stop()
                    deviceRuntime.registration.forgetRegistration()
                    deviceRuntime.firstRun.pairingRemoved()
                }
                .show()
        }
        renderRegistration(root, deviceRuntime.registration.state.value)
        registrationStateJob = lifecycleScope.launch {
            deviceRuntime.registration.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { state -> renderRegistration(root, state) }
        }
        root.findViewById<MaterialButton>(R.id.settings_signalr_force_reconnect).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener {
                deviceRuntime.remoteConnectivity.start()
                deviceRuntime.signalR.forceReconnect()
            }
        }
        renderSignalR(root, deviceRuntime.signalR.state.value, deviceRuntime.signalR.metrics.value)
        signalRStateJob = lifecycleScope.launch {
            deviceRuntime.signalR.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { state -> renderSignalR(root, state, deviceRuntime.signalR.metrics.value) }
        }
        signalRMetricsJob = lifecycleScope.launch {
            deviceRuntime.signalR.metrics
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { metrics -> renderSignalR(root, deviceRuntime.signalR.state.value, metrics) }
        }
    }

    private fun applyServerUrl(
        input: com.google.android.material.textfield.TextInputEditText,
        container: TextInputLayout,
    ): Boolean {
        if (!BuildConfig.DEBUG) return true
        val state = deviceRuntime.registration.configure(input.text?.toString().orEmpty())
        val failure = (state as? RegistrationState.InvalidConfiguration)?.failure
        container.error = failure?.let(::failureMessage)
        return failure == null
    }

    private fun renderSystem(root: View, snapshot: DeviceSnapshot) {
        val connectivity = when (snapshot.network) {
            ConnectivityState.Offline -> getString(R.string.device_connectivity_offline)
            is ConnectivityState.LocalNetwork -> getString(R.string.device_connectivity_local)
            is ConnectivityState.InternetAvailable -> getString(R.string.device_connectivity_internet)
            is ConnectivityState.Limited -> getString(R.string.device_connectivity_limited)
            ConnectivityState.CaptivePortal -> getString(R.string.device_connectivity_captive)
        }
        root.findViewById<TextView>(R.id.settings_connectivity_value).apply {
            text = connectivity
            applyVisionStatusColor(
                when (snapshot.network) {
                    ConnectivityState.Offline -> R.color.vision_status_offline
                    is ConnectivityState.LocalNetwork,
                    is ConnectivityState.InternetAvailable,
                    -> R.color.vision_status_healthy
                    is ConnectivityState.Limited,
                    ConnectivityState.CaptivePortal,
                    -> R.color.vision_status_motion
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_battery_value).apply {
            text = getString(
                R.string.device_battery_format,
                snapshot.battery.levelPercent?.let { "$it%" }
                    ?: getString(R.string.device_value_unavailable),
                when (snapshot.battery.isCharging) {
                    true -> getString(R.string.device_charging)
                    false -> getString(R.string.device_not_charging)
                    null -> getString(R.string.device_value_unavailable)
                },
            )
            applyVisionStatusColor(
                when {
                    snapshot.battery.levelPercent == null -> R.color.vision_status_offline
                    snapshot.battery.levelPercent <= 15 -> R.color.vision_status_critical
                    snapshot.battery.levelPercent <= 30 || snapshot.battery.isPowerSaveMode == true ->
                        R.color.vision_status_motion
                    else -> R.color.vision_status_healthy
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_storage_value).apply {
            text = getString(
                R.string.device_storage_format,
                snapshot.storage.availableBytes?.let(RecordingEngineUiFormatter::storageSize)
                    ?: getString(R.string.device_value_unavailable),
            )
            applyVisionStatusColor(
                if (snapshot.storage.isLow) R.color.vision_status_motion
                else if (snapshot.storage.availableBytes == null) R.color.vision_status_offline
                else R.color.vision_status_healthy,
            )
        }
    }

    private fun renderRegistration(root: View, state: RegistrationState) {
        val operatingMode = deviceRuntime.registration.operatingMode()
        val standalone = operatingMode is CameraOperatingMode.Standalone
        val details = state.details
        root.findViewById<TextView>(R.id.settings_operating_mode).apply {
            text = getString(
                R.string.camera_operating_mode_format,
                getString(
                    if (standalone) {
                        R.string.camera_operating_mode_standalone
                    } else {
                        R.string.camera_operating_mode_hub_managed
                    },
                ),
            )
            applyVisionStatusColor(
                if (standalone) R.color.vision_brand_primary else R.color.vision_status_healthy,
            )
        }
        root.findViewById<TextView>(R.id.settings_hub_status).apply {
            text = getString(
                R.string.camera_hub_status_format,
                if (standalone) {
                    getString(R.string.camera_hub_not_connected)
                } else {
                    registrationStateLabel(state)
                },
            )
            applyVisionStatusColor(
                when {
                    standalone -> R.color.vision_status_offline
                    state is RegistrationState.Registered -> R.color.vision_status_healthy
                    state is RegistrationState.Configuring || state is RegistrationState.Registering ->
                        R.color.vision_brand_primary
                    else -> R.color.vision_status_motion
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_registration_server_url).text = getString(
            R.string.device_server_url_format,
            details?.serverBaseUrl?.ifBlank { getString(R.string.device_value_unavailable) }
                ?: deviceRuntime.registration.configuredBaseUrl().ifBlank {
                    getString(R.string.device_value_unavailable)
                },
        )
        val stateText = if (standalone && state is RegistrationState.Unregistered) {
            getString(R.string.camera_hub_not_connected)
        } else {
            registrationStateLabel(state)
        }
        root.findViewById<TextView>(R.id.settings_registration_status).apply {
            text = getString(R.string.device_registration_status_format, stateText)
            applyVisionStatusColor(
                when (state) {
                    is RegistrationState.Registered -> R.color.vision_status_healthy
                    is RegistrationState.Configuring,
                    is RegistrationState.Registering,
                    -> R.color.vision_brand_primary
                    is RegistrationState.Unregistered -> if (standalone) {
                        R.color.vision_brand_primary
                    } else {
                        R.color.vision_status_offline
                    }
                    is RegistrationState.TokenExpired,
                    is RegistrationState.ConnectionFailed,
                    is RegistrationState.ServerRejected,
                    is RegistrationState.InvalidConfiguration,
                    is RegistrationState.Error,
                    -> R.color.vision_status_critical
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_registration_server_device_id).text = getString(
            R.string.device_server_id_format,
            details?.serverDeviceId ?: getString(R.string.device_value_unavailable),
        )
        root.findViewById<TextView>(R.id.settings_registration_token_expiration).text = getString(
            R.string.device_token_expiration_format,
            formatTime(details?.accessTokenExpiresAtMillis),
        )
        root.findViewById<TextView>(R.id.settings_registration_last_attempt).text = getString(
            R.string.device_last_attempt_format,
            formatTime(details?.lastRegistrationAttemptAtMillis),
        )
        root.findViewById<TextView>(R.id.settings_registration_last_connection).text = getString(
            R.string.device_last_connection_format,
            formatTime(details?.lastSuccessfulConnectionAtMillis),
        )
        val busy = state is RegistrationState.Configuring || state is RegistrationState.Registering
        root.findViewById<MaterialButton>(R.id.settings_scan_hub_qr).apply {
            isEnabled = !busy
            visibility = if (standalone) View.VISIBLE else View.GONE
        }
        root.findViewById<MaterialButton>(R.id.settings_test_connection).isEnabled = !busy
        root.findViewById<MaterialButton>(R.id.settings_register_device).apply {
            isEnabled = !busy
            visibility = if (standalone || state !is RegistrationState.Registered) View.VISIBLE else View.GONE
            text = getString(
                if (standalone) {
                    R.string.pairing_manual_setup
                } else if (state is RegistrationState.ConnectionFailed ||
                    state is RegistrationState.ServerRejected ||
                    state is RegistrationState.TokenExpired ||
                    state is RegistrationState.Error
                ) {
                    R.string.device_retry_registration
                } else {
                    R.string.device_register
                },
            )
        }
        root.findViewById<MaterialButton>(R.id.settings_forget_registration).apply {
            isEnabled = !busy
            visibility = if (
                operatingMode is CameraOperatingMode.HubManaged ||
                (state is RegistrationState.Error && state.failure is RegistrationFailure.SecureStorageFailure)
            ) View.VISIBLE else View.GONE
        }
        root.findViewById<View>(R.id.settings_realtime_card).visibility =
            if (standalone) View.GONE else View.VISIBLE

        listOf(
            R.id.settings_registration_server_url,
            R.id.settings_registration_server_device_id,
            R.id.settings_registration_token_expiration,
            R.id.settings_registration_last_attempt,
            R.id.settings_registration_last_connection,
        ).forEach { id ->
            root.findViewById<View>(id).visibility = if (standalone) View.GONE else View.VISIBLE
        }
    }

    private fun registrationStateLabel(state: RegistrationState): String = when (state) {
        is RegistrationState.Unregistered -> getString(R.string.device_registration_unregistered)
        is RegistrationState.Configuring -> getString(R.string.device_registration_configuring)
        is RegistrationState.Registering -> getString(R.string.device_registration_registering)
        is RegistrationState.Registered -> getString(R.string.device_registration_registered)
        is RegistrationState.TokenExpired -> getString(R.string.device_registration_expired)
        is RegistrationState.ConnectionFailed -> getString(R.string.device_registration_connection_failed) +
            " · " + failureMessage(state.failure)
        is RegistrationState.ServerRejected -> getString(R.string.device_registration_rejected) +
            " · " + failureMessage(state.failure)
        is RegistrationState.InvalidConfiguration -> getString(R.string.device_registration_invalid) +
            " · " + failureMessage(state.failure)
        is RegistrationState.Error -> getString(R.string.device_registration_error) +
            " · " + failureMessage(state.failure)
    }

    private fun completePairing(payload: String) {
        lifecycleScope.launch {
            val result = deviceRuntime.registration.pairNow(payload)
            if (result is RegistrationState.Registered) {
                deviceRuntime.firstRun.pairingSucceeded(cameraPermissionGranted = true)
            }
            Toast.makeText(
                this@AppSettingsActivity,
                if (result is RegistrationState.Registered) {
                    R.string.hub_pairing_complete
                } else {
                    R.string.hub_pairing_failed
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun renderSignalR(
        root: View,
        state: SignalRState,
        metrics: SignalRConnectionMetrics,
    ) {
        val stateText = getString(
            when (state) {
                SignalRState.Stopped -> R.string.signalr_stopped
                is SignalRState.Disconnected -> R.string.signalr_disconnected
                is SignalRState.Connecting -> R.string.signalr_connecting
                is SignalRState.Connected -> R.string.signalr_connected
                is SignalRState.Reconnecting -> R.string.signalr_reconnecting
                is SignalRState.AuthenticationFailed -> R.string.signalr_authentication_failed
                is SignalRState.ServerUnavailable -> R.string.signalr_server_unavailable
                is SignalRState.Error -> R.string.signalr_error
            },
        )
        root.findViewById<TextView>(R.id.settings_signalr_state).apply {
            text = getString(R.string.signalr_state_format, stateText)
            applyVisionStatusColor(
                when (state) {
                    is SignalRState.Connected -> R.color.vision_status_healthy
                    is SignalRState.Connecting -> R.color.vision_brand_primary
                    is SignalRState.Reconnecting -> R.color.vision_status_motion
                    SignalRState.Stopped,
                    is SignalRState.Disconnected,
                    -> R.color.vision_status_offline
                    is SignalRState.AuthenticationFailed,
                    is SignalRState.ServerUnavailable,
                    is SignalRState.Error,
                    -> R.color.vision_status_critical
                },
            )
        }
        root.findViewById<TextView>(R.id.settings_signalr_server_url).text = getString(
            R.string.signalr_server_url_format,
            deviceRuntime.signalR.configuredHubUrl().ifBlank { getString(R.string.device_value_unavailable) },
        )
        root.findViewById<TextView>(R.id.settings_signalr_connection_status).text = getString(
            R.string.signalr_connection_format,
            getString(
                if (state is SignalRState.Connected) {
                    R.string.signalr_connection_active
                } else {
                    R.string.signalr_connection_inactive
                },
            ),
        )
        root.findViewById<TextView>(R.id.settings_signalr_last_heartbeat).text = getString(
            R.string.signalr_last_heartbeat_format,
            formatTime(metrics.lastHeartbeatAtMillis),
        )
        root.findViewById<TextView>(R.id.settings_signalr_reconnect_attempts).text = getString(
            R.string.signalr_reconnect_attempts_format,
            metrics.reconnectAttempts,
        )
        root.findViewById<MaterialButton>(R.id.settings_signalr_force_reconnect).isEnabled =
            state !is SignalRState.Stopped && state !is SignalRState.Connecting
    }

    private fun failureMessage(failure: RegistrationFailure): String = getString(
        when (failure) {
            is RegistrationFailure.NetworkUnavailable -> R.string.device_failure_network_unavailable
            is RegistrationFailure.Timeout -> R.string.device_failure_timeout
            is RegistrationFailure.InvalidServerUrl -> R.string.device_failure_invalid_url
            is RegistrationFailure.CleartextBlocked -> R.string.device_failure_cleartext
            is RegistrationFailure.ServerUnreachable -> R.string.device_failure_unreachable
            is RegistrationFailure.ValidationRejected -> R.string.device_failure_rejected
            is RegistrationFailure.Unauthorized -> R.string.device_failure_unauthorized
            is RegistrationFailure.Conflict -> R.string.device_failure_conflict
            is RegistrationFailure.SerializationFailure -> R.string.device_failure_serialization
            is RegistrationFailure.SecureStorageFailure -> R.string.device_failure_storage
            is RegistrationFailure.TokenInvalid -> R.string.device_failure_token
            is RegistrationFailure.UnexpectedFailure -> R.string.device_failure_unexpected
        },
    )

    private fun formatTime(value: Long?): String = value?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: getString(R.string.device_value_unavailable)

    private fun observeSnapshot(root: View, render: (View, DeviceSnapshot) -> Unit) {
        render(root, deviceRuntime.device.snapshot.value)
        deviceStateJob = lifecycleScope.launch {
            deviceRuntime.device.snapshot
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { snapshot -> render(root, snapshot) }
        }
    }

    private fun bindCamera(root: View) {
        val settings = viewModel.coordinator.state.value.draft.camera
        val lens = root.findViewById<ChipGroup>(R.id.settings_camera_lens)
        lens.check(if (settings.camera == RecordingCamera.FRONT) R.id.settings_camera_front else R.id.settings_camera_rear)
        lens.setOnCheckedStateChangeListener { _, checked ->
            viewModel.coordinator.updateCamera(
                viewModel.coordinator.state.value.draft.camera.copy(
                    camera = if (checked.firstOrNull() == R.id.settings_camera_front) {
                        RecordingCamera.FRONT
                    } else RecordingCamera.REAR,
                ),
            )
        }
        root.findViewById<MaterialSwitch>(R.id.settings_timestamp_enabled).apply {
            isChecked = settings.overlay.showDateTime
            setOnCheckedChangeListener { _, enabled ->
                viewModel.coordinator.updateCamera(
                    viewModel.coordinator.state.value.draft.camera.copy(
                        overlay = RecordingOverlayConfiguration(enabled),
                    ),
                )
            }
        }
    }

    private fun bindRecording(root: View) {
        val state = viewModel.coordinator.state.value.draft
        val settings = state.recording
        root.findViewById<View>(R.id.settings_recording_debug_group).visibility =
            if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        root.findViewById<ChipGroup>(R.id.settings_recording_quality).apply {
            check(
                when (settings.profileId) {
                    RecordingProfileId.LOW -> R.id.settings_quality_low
                    RecordingProfileId.STANDARD -> R.id.settings_quality_standard
                    RecordingProfileId.HIGH -> R.id.settings_quality_high
                },
            )
            setOnCheckedStateChangeListener { _, checked ->
                val profile = when (checked.firstOrNull()) {
                    R.id.settings_quality_low -> RecordingProfileId.LOW
                    R.id.settings_quality_high -> RecordingProfileId.HIGH
                    else -> RecordingProfileId.STANDARD
                }
                viewModel.coordinator.updateRecording(
                    viewModel.coordinator.state.value.draft.recording.copy(profileId = profile),
                )
            }
        }
        root.findViewById<View>(R.id.settings_duration_15).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.settings_duration_30).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        root.findViewById<ChipGroup>(R.id.settings_segment_duration).apply {
            check(durationChip(settings.segmentDurationMillis))
            setOnCheckedStateChangeListener { _, checked ->
                viewModel.coordinator.updateRecording(
                    viewModel.coordinator.state.value.draft.recording.copy(
                        segmentDurationMillis = durationValue(checked.firstOrNull()),
                    ),
                )
            }
        }
        root.findViewById<MaterialSwitch>(R.id.settings_audio_enabled).apply {
            isChecked = state.camera.audioEnabled
            setOnCheckedChangeListener { _, enabled ->
                viewModel.coordinator.updateCamera(
                    viewModel.coordinator.state.value.draft.camera.copy(audioEnabled = enabled),
                )
            }
        }
        root.findViewById<MaterialSwitch>(R.id.settings_simulate_storage_warning).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            isChecked = settings.simulateStorageWarning && BuildConfig.DEBUG
            setOnCheckedChangeListener { _, enabled ->
                viewModel.coordinator.updateRecording(
                    viewModel.coordinator.state.value.draft.recording.copy(
                        simulateStorageWarning = enabled && BuildConfig.DEBUG,
                    ),
                )
                renderStorage(root, enabled)
            }
        }
        renderStorage(root, settings.simulateStorageWarning)
    }

    private fun bindMotion(root: View) {
        val config = viewModel.coordinator.state.value.draft.motion
        root.findViewById<MaterialSwitch>(R.id.settings_motion_enabled).apply {
            isChecked = config.enabled
            setOnCheckedChangeListener { _, enabled -> updateMotion { copy(enabled = enabled) } }
        }
        val sensitivity = root.findViewById<ChipGroup>(R.id.settings_motion_sensitivity)
        val advancedContainer = root.findViewById<View>(R.id.settings_advanced_sensitivity_container)
        val advancedValue = root.findViewById<TextView>(R.id.settings_advanced_sensitivity_value)
        val slider = root.findViewById<Slider>(R.id.settings_advanced_sensitivity_slider)
        sensitivity.check(sensitivityChip(config.sensitivity))
        slider.value = config.advancedSensitivity.toFloat()
        renderAdvancedSensitivity(advancedContainer, advancedValue, slider, config)
        sensitivity.setOnCheckedStateChangeListener { _, checked ->
            val selected = sensitivityValue(checked.firstOrNull())
            updateMotion { copy(sensitivity = selected) }
            renderAdvancedSensitivity(
                advancedContainer,
                advancedValue,
                slider,
                viewModel.coordinator.state.value.draft.motion,
            )
        }
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val safe = MotionSensitivityPolicy.sanitizeAdvancedValue(value.toInt())
            updateMotion { copy(advancedSensitivity = safe) }
            renderAdvancedSensitivity(advancedContainer, advancedValue, slider, viewModel.coordinator.state.value.draft.motion)
        }
        bindMotionTimingGroups(root, config)
        root.findViewById<TextView>(R.id.settings_motion_debug_metrics).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            if (BuildConfig.DEBUG) text = profileSummary(viewModel.coordinator.state.value.draft.motion)
        }
        deviceStateJob = lifecycleScope.launch {
            deviceRuntime.device.snapshot
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    val latest = deviceRuntime.motionSettings.load()
                    if (viewModel.coordinator.refreshMotion(latest)
                        && viewModel.coordinator.state.value.navigation.selectedSection == SettingsSection.MOTION_DETECTION
                    ) {
                        renderSelectedSection()
                    }
                }
        }
    }

    private fun bindMotionTimingGroups(root: View, config: MotionDetectionConfig) {
        bindTiming(root, R.id.settings_motion_trigger, triggerChip(config.triggerDelayMillis)) { id ->
            updateMotion { copy(triggerDelayMillis = when (id) { R.id.settings_trigger_immediate -> 0L; R.id.settings_trigger_2 -> 2_000L; else -> 1_000L }) }
        }
        root.findViewById<View>(R.id.settings_hold_3).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        bindTiming(root, R.id.settings_motion_hold, holdChip(config.stopDelayMillis)) { id ->
            updateMotion { copy(stopDelayMillis = when (id) { R.id.settings_hold_3 -> 3_000L; R.id.settings_hold_5 -> 5_000L; R.id.settings_hold_20 -> 20_000L; R.id.settings_hold_30 -> 30_000L; else -> 10_000L }) }
        }
        bindTiming(root, R.id.settings_motion_cooldown, cooldownChip(config.cooldownMillis)) { id ->
            updateMotion { copy(cooldownMillis = when (id) { R.id.settings_cooldown_3 -> 3_000L; R.id.settings_cooldown_10 -> 10_000L; else -> 5_000L }) }
        }
    }

    private fun bindTiming(root: View, groupId: Int, checkedId: Int, update: (Int?) -> Unit) {
        root.findViewById<ChipGroup>(groupId).apply {
            check(checkedId)
            setOnCheckedStateChangeListener { _, checked -> update(checked.firstOrNull()) }
        }
    }

    private fun bindSmartDetection(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.settings_smart_features)
        SMART_FEATURES.forEach { feature ->
            container.addView(CapabilityFeatureRowView(this).apply {
                bind(
                    getString(feature.name),
                    getString(feature.description),
                    entitlements.getAccess(feature.capability),
                )
            })
        }
    }

    private fun updateMotion(transform: MotionDetectionConfig.() -> MotionDetectionConfig) {
        val safe = MotionSettingsPolicy.normalize(
            viewModel.coordinator.state.value.draft.motion.transform(),
            BuildConfig.DEBUG,
        )
        viewModel.coordinator.updateMotion(safe)
    }

    private fun renderAdvancedSensitivity(container: View, label: TextView, slider: Slider, config: MotionDetectionConfig) {
        val active = config.resolvedSensitivity().advancedSensitivityActive
        container.visibility = if (active) View.VISIBLE else View.GONE
        slider.isEnabled = active
        val value = MotionSensitivityPolicy.sanitizeAdvancedValue(config.advancedSensitivity)
        label.text = getString(R.string.motion_sensitivity_value, value)
        slider.contentDescription = getString(R.string.motion_sensitivity_accessibility_value, value)
        if (slider.value.toInt() != value) slider.value = value.toFloat()
        rootDebugMetrics(label.rootView, config)
    }

    private fun rootDebugMetrics(root: View, config: MotionDetectionConfig) {
        if (!BuildConfig.DEBUG) return
        root.findViewById<TextView?>(R.id.settings_motion_debug_metrics)?.text = profileSummary(config)
    }

    private fun profileSummary(config: MotionDetectionConfig): String {
        val p = MotionSensitivityPolicy.profile(config.sensitivity, config.advancedSensitivity)
        return getString(R.string.motion_profile_debug, p.threshold, p.noiseTolerance, p.changedAreaThreshold, p.brightnessChangeTolerance, p.requiredPositiveFrames)
    }

    private fun renderStorage(root: View, simulated: Boolean) {
        val value = root.findViewById<TextView>(R.id.settings_storage_value)
        val warning = root.findViewById<TextView>(R.id.settings_storage_warning)
        val check = storageCheck
        value.text = when (check) {
            is RecordingStorageCheck.Available -> getString(R.string.engine_storage_value, RecordingEngineUiFormatter.storageSize(check.availableBytes))
            is RecordingStorageCheck.Unavailable -> getString(R.string.engine_storage_unavailable)
            null -> getString(R.string.engine_storage_loading)
        }
        val low = (check as? RecordingStorageCheck.Available)?.level != null &&
            (check as RecordingStorageCheck.Available).level != RecordingStorageLevel.HEALTHY
        warning.visibility = if (low || simulated) View.VISIBLE else View.GONE
    }

    private fun loadStorageStatus() {
        lifecycleScope.launch {
            val state = viewModel.coordinator.state.value.draft
            storageCheck = AndroidRecordingStorageGateway(applicationContext)
                .validateForStart(state.recording.toProfile(state.camera.audioEnabled).storagePolicy)
            if (viewModel.coordinator.state.value.navigation.selectedSection == SettingsSection.RECORDING) {
                renderSelectedSection()
            }
        }
    }

    private fun readInitialSection(): SettingsSection = intent.getStringExtra(EXTRA_SECTION)
        ?.let { stored -> SettingsSection.entries.firstOrNull { it.name == stored } }
        ?: SettingsSection.CAMERA

    private fun SettingsSection.titleResource(): Int = when (this) {
        SettingsSection.CAMERA -> R.string.settings_camera_title
        SettingsSection.RECORDING -> R.string.settings_recording_title
        SettingsSection.MOTION_DETECTION -> R.string.settings_motion_title
        SettingsSection.MONITORING -> R.string.settings_monitoring_title
        SettingsSection.SMART_DETECTION -> R.string.settings_smart_title
        SettingsSection.DEVICE -> R.string.settings_device_title
        SettingsSection.SYSTEM_CONNECTIVITY -> R.string.settings_system_title
    }

    private fun sensitivityChip(value: MotionSensitivity) = when (value) {
        MotionSensitivity.LOW -> R.id.settings_sensitivity_low
        MotionSensitivity.MEDIUM -> R.id.settings_sensitivity_medium
        MotionSensitivity.HIGH -> R.id.settings_sensitivity_high
        MotionSensitivity.ADVANCED -> R.id.settings_sensitivity_advanced
    }
    private fun sensitivityValue(id: Int?) = when (id) { R.id.settings_sensitivity_low -> MotionSensitivity.LOW; R.id.settings_sensitivity_high -> MotionSensitivity.HIGH; R.id.settings_sensitivity_advanced -> MotionSensitivity.ADVANCED; else -> MotionSensitivity.MEDIUM }
    private fun triggerChip(v: Long) = when (v) { 0L -> R.id.settings_trigger_immediate; 2_000L -> R.id.settings_trigger_2; else -> R.id.settings_trigger_1 }
    private fun holdChip(v: Long) = when (v) { 3_000L -> R.id.settings_hold_3; 5_000L -> R.id.settings_hold_5; 20_000L -> R.id.settings_hold_20; 30_000L -> R.id.settings_hold_30; else -> R.id.settings_hold_10 }
    private fun cooldownChip(v: Long) = when (v) { 3_000L -> R.id.settings_cooldown_3; 10_000L -> R.id.settings_cooldown_10; else -> R.id.settings_cooldown_5 }
    private fun durationChip(v: Long) = when (v) { RecordingEngineSettingsPolicy.FIFTEEN_SECONDS -> R.id.settings_duration_15; RecordingEngineSettingsPolicy.THIRTY_SECONDS -> R.id.settings_duration_30; RecordingEngineSettingsPolicy.ONE_MINUTE -> R.id.settings_duration_1m; RecordingEngineSettingsPolicy.TEN_MINUTES -> R.id.settings_duration_10m; else -> R.id.settings_duration_5m }
    private fun durationValue(id: Int?) = when (id) { R.id.settings_duration_15 -> RecordingEngineSettingsPolicy.FIFTEEN_SECONDS; R.id.settings_duration_30 -> RecordingEngineSettingsPolicy.THIRTY_SECONDS; R.id.settings_duration_1m -> RecordingEngineSettingsPolicy.ONE_MINUTE; R.id.settings_duration_10m -> RecordingEngineSettingsPolicy.TEN_MINUTES; else -> RecordingEngineSettingsPolicy.FIVE_MINUTES }

    private fun Enum<*>.displayName(): String = name.lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private data class SmartFeature(val capability: AppCapability, val name: Int, val description: Int)

    private data class SettingsHomeItem(
        val section: SettingsSection,
        val title: Int,
        val summary: Int,
        val icon: Int,
    )

    companion object {
        const val EXTRA_SETTINGS_CHANGED = "settings_changed"
        private const val EXTRA_SECTION = "settings_section"
        private const val EXTRA_SHOW_HOME = "show_settings_home"
        private const val STATE_DEVICE_DETAILS = "device_details_expanded"
        private val SETTINGS_HOME_ITEMS = listOf(
            SettingsHomeItem(
                SettingsSection.CAMERA,
                R.string.settings_camera_title,
                R.string.settings_home_camera_summary,
                R.drawable.ic_flip_camera_24,
            ),
            SettingsHomeItem(
                SettingsSection.RECORDING,
                R.string.settings_recording_title,
                R.string.settings_home_recording_summary,
                R.drawable.ic_record_filled_24,
            ),
            SettingsHomeItem(
                SettingsSection.MOTION_DETECTION,
                R.string.settings_home_detection_title,
                R.string.settings_home_detection_summary,
                R.drawable.ic_motion_24,
            ),
            SettingsHomeItem(
                SettingsSection.MONITORING,
                R.string.settings_monitoring_title,
                R.string.settings_home_monitoring_summary,
                R.drawable.ic_live_24,
            ),
            SettingsHomeItem(
                SettingsSection.DEVICE,
                R.string.settings_device_title,
                R.string.settings_home_device_summary,
                R.drawable.ic_info_24,
            ),
            SettingsHomeItem(
                SettingsSection.SYSTEM_CONNECTIVITY,
                R.string.settings_home_connection_title,
                R.string.settings_home_connection_summary,
                R.drawable.ic_hub_24,
            ),
        )
        private val SMART_FEATURES = listOf(
            SmartFeature(AppCapability.SMART_PERSON_DETECTION, R.string.smart_person_name, R.string.smart_person_description),
            SmartFeature(AppCapability.ANIMAL_DETECTION, R.string.smart_animal_name, R.string.smart_animal_description),
            SmartFeature(AppCapability.VEHICLE_DETECTION, R.string.smart_vehicle_name, R.string.smart_vehicle_description),
            SmartFeature(AppCapability.KNOWN_PERSON_RECOGNITION, R.string.smart_known_person_name, R.string.smart_known_person_description),
            SmartFeature(AppCapability.KNOWN_PERSON_RECOGNITION, R.string.smart_unknown_person_name, R.string.smart_unknown_person_description),
            SmartFeature(AppCapability.ADVANCED_DETECTION_RULES, R.string.smart_ignore_identity_name, R.string.smart_ignore_identity_description),
            SmartFeature(AppCapability.ADVANCED_DETECTION_RULES, R.string.smart_notify_identity_name, R.string.smart_notify_identity_description),
            SmartFeature(AppCapability.ADVANCED_DETECTION_RULES, R.string.smart_rules_name, R.string.smart_rules_description),
            SmartFeature(AppCapability.ADVANCED_DETECTION_RULES, R.string.smart_zones_name, R.string.smart_zones_description),
        )

        fun intent(context: Context, destination: SettingsDestination): Intent =
            Intent(context, AppSettingsActivity::class.java).apply {
                if (destination == SettingsDestination.general()) {
                    putExtra(EXTRA_SHOW_HOME, true)
                } else {
                    putExtra(EXTRA_SECTION, destination.section.name)
                }
            }
    }
}
