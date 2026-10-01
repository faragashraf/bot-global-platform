package com.ashraffarag.sentricam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ashraffarag.sentricam.camera.android.CameraXPreviewController
import com.ashraffarag.sentricam.camera.domain.CameraLensFacing
import com.ashraffarag.sentricam.camera.domain.CameraLensSwitchController
import com.ashraffarag.sentricam.camera.domain.CameraPreviewController
import com.ashraffarag.sentricam.camera.presentation.LiveCameraControlPolicy
import com.ashraffarag.sentricam.camera.presentation.LiveFlashMode
import com.ashraffarag.sentricam.camera.presentation.ZoomRequestPresentation
import com.ashraffarag.sentricam.camera.presentation.ZoomRequestPresentationGuard
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraXControl
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlIds
import com.ashraffarag.sentricam.databinding.ActivityMainBinding
import com.ashraffarag.sentricam.communication.signalr.presentation.RealtimeStatusPresenter
import com.ashraffarag.sentricam.device.android.DeviceRuntime
import com.ashraffarag.sentricam.device.command.DeviceCommand
import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.handle
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.domain.CameraState
import com.ashraffarag.sentricam.device.domain.DeviceCameraLens
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.device.integration.AppRecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.integration.MotionEngineCommandPort
import com.ashraffarag.sentricam.device.integration.RecordingEngineCommandPort
import com.ashraffarag.sentricam.device.registration.RegistrationState
import com.ashraffarag.sentricam.device.time.DeviceTimeValidation
import com.ashraffarag.sentricam.device.time.DeviceTimeValidationAction
import com.ashraffarag.sentricam.device.time.DeviceTimeValidationKind
import com.ashraffarag.sentricam.device.time.action
import com.ashraffarag.sentricam.live.android.LiveViewDiagnostics
import com.ashraffarag.sentricam.live.presentation.LiveStatusPresenter
import com.ashraffarag.sentricam.localization.domain.AppLanguage
import com.ashraffarag.sentricam.motion.android.CameraXMotionDetectionEngine
import com.ashraffarag.sentricam.motion.android.SharedPreferencesMotionSettingsHintStore
import com.ashraffarag.sentricam.motion.android.ui.MotionActionViewBinder
import com.ashraffarag.sentricam.motion.android.ui.MotionToolbarButtonController
import com.ashraffarag.sentricam.motion.capability.ManualRecordingRequestResult
import com.ashraffarag.sentricam.motion.capability.MotionRecordingCoordinator
import com.ashraffarag.sentricam.motion.capability.MotionRecordingCoordinatorState
import com.ashraffarag.sentricam.motion.capability.MotionRecordingRequestProvider
import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsPolicy
import com.ashraffarag.sentricam.motion.presentation.MotionActionBindingController
import com.ashraffarag.sentricam.motion.presentation.MotionActionController
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsHintPolicy
import com.ashraffarag.sentricam.motion.presentation.MotionStatusUiFactory
import com.ashraffarag.sentricam.monitoring.domain.MonitoringConfiguration
import com.ashraffarag.sentricam.monitoring.domain.CameraOwner
import com.ashraffarag.sentricam.monitoring.domain.CameraOwnershipResult
import com.ashraffarag.sentricam.monitoring.domain.MonitoringPolicy
import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.monitoring.domain.MonitoringTransitionResult
import com.ashraffarag.sentricam.onboarding.FirstRunCoordinator
import com.ashraffarag.sentricam.pairing.android.QrPairingScannerActivity
import com.ashraffarag.sentricam.recording.android.AndroidRecordingConfirmationVibrator
import com.ashraffarag.sentricam.recording.domain.RecordingDurationFormatter
import com.ashraffarag.sentricam.recording.engine.android.CameraXRecordingEngine
import com.ashraffarag.sentricam.recording.engine.android.SharedPreferencesRecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineSettingsPolicy
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineStatusUiFactory
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineUiSettings
import com.ashraffarag.sentricam.recording.library.android.ui.RecordingsActivity
import com.ashraffarag.sentricam.recording.overlay.android.CameraXRecordingOverlayEngine
import com.ashraffarag.sentricam.recording.presentation.RecordingStartFeedbackController
import com.ashraffarag.sentricam.recording.presentation.RecordingStartFeedbackEvent
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettings
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera as SettingsRecordingCamera
import com.ashraffarag.sentricam.settings.android.AppSettingsActivity
import com.ashraffarag.sentricam.settings.capability.MotionConfigApplyMode
import com.ashraffarag.sentricam.settings.capability.MotionLiveConfigPolicy
import com.ashraffarag.sentricam.settings.domain.SettingsDestination
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val deviceRuntime: DeviceRuntime
        get() = (application as SentriCamApplication).deviceRuntime
    private val languageController
        get() = (application as SentriCamApplication).languageController
    private lateinit var cameraPreviewController: CameraPreviewController
    private lateinit var recordingEngine: RecordingEngine
    private lateinit var engineSettingsRepository: RecordingEngineSettingsRepository
    private lateinit var settingsRepository: RecordingSettingsRepository
    private lateinit var motionSettingsRepository: MotionSettingsRepository
    private lateinit var motionEngine: CameraXMotionDetectionEngine
    private lateinit var motionRecordingCoordinator: MotionRecordingCoordinator
    private lateinit var motionToolbarButtonController: MotionToolbarButtonController
    private lateinit var motionSettingsHintPolicy: MotionSettingsHintPolicy
    private lateinit var recordingStartFeedbackController: RecordingStartFeedbackController
    private var appliedSettings = RecordingSettings()
    private var engineUiSettings = RecordingEngineUiSettings()
    private var motionConfig = MotionDetectionConfig()
    private var overlayEngine: CameraXRecordingOverlayEngine? = null
    private var activityCameraControlHardware: AndroidCameraXControl? = null
    private var zoomApplyJob: Job? = null
    private val zoomRequestPresentation = ZoomRequestPresentationGuard()
    private var permissionRequestInFlight = false
    private var audioPermissionDecisionInFlight = false
    private var cameraStarting = false
    private var cameraActive = false
    private var lensSwitchController = CameraLensSwitchController(CameraLensFacing.REAR)
    private var cameraConfigurationGeneration = 0
    private var audioPermissionDialog: AlertDialog? = null
    private var recordingStateJob: Job? = null
    private var motionStateJob: Job? = null
    private var motionCoordinatorStateJob: Job? = null
    private var motionAnalysisFailure: Throwable? = null
    private var motionConfigurationInProgress = false
    private var monitoringServiceMode = false
    private var activityCameraHost = false
    private var serviceStateJob: Job? = null
    private var livePreviewJob: Job? = null
    private var realtimeStatusJob: Job? = null
    private var liveStatusJob: Job? = null
    private var timeValidationJob: Job? = null
    private var registrationStateJob: Job? = null
    private val cameraOwnerToken = UUID.randomUUID().toString()

    private val permissionPreferences by lazy {
        getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestInFlight = false
        if (granted) {
            if (monitoringServiceMode) requestNotificationPermissionThenStart() else startCameraPreview()
        } else {
            showCameraPermissionDenied()
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioPermissionDecisionInFlight = false
        if (granted) {
            if (monitoringServiceMode) {
                restartMonitoringForMicrophoneThenRecord()
            } else {
                startRecording(audioPermissionGranted = true)
            }
        } else {
            binding.recordingResult.setText(R.string.microphone_permission_denied_silent)
            startRecording(audioPermissionGranted = false)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startMonitoringService()
    }

    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) applyUnifiedSettings()
    }

    private val pairingScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = result.data?.getStringExtra(QrPairingScannerActivity.EXTRA_PAIRING_PAYLOAD)
        when {
            result.resultCode == RESULT_OK && !payload.isNullOrBlank() -> completePairing(payload)
            result.data?.getStringExtra(QrPairingScannerActivity.EXTRA_ACTION_REQUIRED) ==
                QrPairingScannerActivity.ACTION_MANUAL_SETUP -> openUnifiedSettings(SettingsDestination.connectivity())
            result.data?.getStringExtra(QrPairingScannerActivity.EXTRA_ACTION_REQUIRED) ==
                QrPairingScannerActivity.ACTION_CAMERA_PERMISSION -> showCameraPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recordingStartFeedbackController = RecordingStartFeedbackController(
            AndroidRecordingConfirmationVibrator(applicationContext),
        )
        applyResponsiveLayoutResources()
        // Operational status must remain readable even when the monitoring-service placeholder
        // covers the hidden phone preview. These stay separate from the Live and recording states.
        binding.liveStatus.bringToFront()
        binding.realtimeStatus.bringToFront()
        binding.timeValidationPanel.bringToFront()
        binding.firstRunPanel.bringToFront()
        binding.cameraToolbar.bringToFront()
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=preview_view_created attached=${binding.previewView.isAttachedToWindow} " +
                "implementation=${binding.previewView.implementationMode} " +
                "provider=${binding.previewView.surfaceProvider.identity()}"
        }
        binding.previewView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                    "event=preview_view_attached visible=${view.visibility} size=${view.width}x${view.height}"
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                    "event=preview_view_detached visible=${view.visibility} size=${view.width}x${view.height}"
                }
            }
        })
        binding.previewView.previewStreamState.observe(this) { state ->
            LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                "event=preview_stream state=$state visible=${binding.previewView.visibility} " +
                    "attached=${binding.previewView.isAttachedToWindow} " +
                    "size=${binding.previewView.width}x${binding.previewView.height}"
            }
        }
        deviceRuntime.livePreview.attachSurfaceProvider(binding.previewView.surfaceProvider)
        livePreviewJob = lifecycleScope.launch {
            deviceRuntime.livePreview.presentation
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { presentation ->
                    renderLivePreviewVisibility(presentation)
                }
        }
        motionToolbarButtonController = MotionToolbarButtonController(binding.motionSettingsButton)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settingsRepository = SharedPreferencesRecordingSettingsRepository(applicationContext)
        engineSettingsRepository = SharedPreferencesRecordingEngineSettingsRepository(
            applicationContext,
            BuildConfig.DEBUG,
        )
        engineUiSettings = engineSettingsRepository.load()
        motionSettingsRepository = deviceRuntime.motionSettings
        motionConfig = motionSettingsRepository.load()
        monitoringServiceMode = deviceRuntime.monitoringSettings.load().enabled
        if (!monitoringServiceMode) {
            configureCamera(settingsRepository.load())
        } else {
            appliedSettings = settingsRepository.load()
            observeMonitoringServiceState()
        }
        motionSettingsHintPolicy = MotionSettingsHintPolicy(
            SharedPreferencesMotionSettingsHintStore(applicationContext),
        )
        val motionActionController = MotionActionController(
            toggleMonitoring = {
                toggleMotionDetection()
                showMotionSettingsHintOnce()
            },
            openSettings = {
                openUnifiedSettings(SettingsDestination.motion())
            },
        )
        MotionActionViewBinder(
            primaryButton = binding.motionSettingsButton,
            settingsFallbackButton = null,
            bindingController = MotionActionBindingController(motionActionController),
            longPressHint = getString(R.string.motion_long_press_hint),
            accessibilitySettingsLabel = getString(R.string.motion_accessibility_open_settings),
        ).bind()
        TooltipCompat.setTooltipText(
            binding.languageButton,
            getString(R.string.language_action_content_description),
        )
        binding.languageButton.setOnClickListener { showLanguageSelector() }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) scheduleZoom(value)
        }
        binding.flashButton.setOnClickListener { showFlashSelector() }
        TooltipCompat.setTooltipText(binding.flashButton, getString(R.string.camera_flash_action))
        binding.recordingButton.setOnClickListener { handleRecordingToggle() }
        binding.settingsButton.setOnClickListener {
            openUnifiedSettings(SettingsDestination.general())
        }
        // TooltipCompat owns the long-click listener on API 23–25, so install it first.
        TooltipCompat.setTooltipText(binding.settingsButton, getString(R.string.settings_open_recording))
        binding.settingsButton.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openUnifiedSettings(SettingsDestination.recording())
            true
        }
        binding.recordingsButton.setOnClickListener { openRecordingsLibrary() }
        binding.cameraSwitchButton.setOnClickListener {
            requestCameraSwitch()
        }
        binding.firstRunScan.setOnClickListener {
            pairingScannerLauncher.launch(QrPairingScannerActivity.intent(this))
        }
        binding.firstRunManual.setOnClickListener {
            openUnifiedSettings(SettingsDestination.connectivity())
        }
        deviceRuntime.firstRun.detectLaunch(
            existingRegistration = deviceRuntime.operatingMode.value is CameraOperatingMode.HubManaged,
        )
        renderFirstRun(
            deviceRuntime.registration.state.value,
            deviceRuntime.operatingMode.value,
        )
        registrationStateJob = lifecycleScope.launch {
            combine(deviceRuntime.registration.state, deviceRuntime.operatingMode) { state, mode ->
                state to mode
            }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { (state, mode) -> renderFirstRun(state, mode) }
        }
        binding.realtimeStatus.setReconnectAction {
            deviceRuntime.remoteConnectivity.start()
            deviceRuntime.signalR.forceReconnect()
        }
        realtimeStatusJob = lifecycleScope.launch {
            combine(
                deviceRuntime.signalR.state,
                deviceRuntime.signalR.metrics,
                deviceRuntime.operatingMode,
            ) { state, metrics, mode ->
                RealtimeStatusPresenter.present(
                    state,
                    metrics,
                    deviceRuntime.signalR.configuredHubUrl(),
                    mode,
                )
            }.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { binding.realtimeStatus.render(it, BuildConfig.DEBUG) }
        }
        lifecycleScope.launch {
            deviceRuntime.signalR.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { state ->
                    if (state is com.ashraffarag.sentricam.communication.signalr.SignalRState.Connected) {
                        deviceRuntime.firstRun.hubConnected()
                    }
                }
        }
        liveStatusJob = lifecycleScope.launch {
            deviceRuntime.liveSessionState
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { binding.liveStatus.render(LiveStatusPresenter.present(it)) }
        }
        timeValidationJob = lifecycleScope.launch {
            deviceRuntime.timeValidation.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect(::renderTimeValidation)
        }
        renderMotionState(
            if (::motionEngine.isInitialized) motionEngine.state.value else deviceRuntime.device.state.value.motion,
        )

        when {
            hasPermission(Manifest.permission.CAMERA) -> {
                if (monitoringServiceMode) requestNotificationPermissionThenStart() else startCameraPreview()
            }
            permissionWasRequested(KEY_CAMERA_PERMISSION_REQUESTED) -> showCameraPermissionDenied()
            else -> requestCameraPermission()
        }
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayoutResources()
    }

    override fun onResume() {
        super.onResume()
        deviceRuntime.markActivity()
        // Android can deny a camera foreground-service restart after process death while the app
        // is backgrounded. Opening the camera is the system-authorized recovery boundary; retry
        // Realtime ownership immediately instead of waiting for registration to emit again.
        if (deviceRuntime.operatingMode.value is CameraOperatingMode.HubManaged &&
            deviceRuntime.registration.state.value is RegistrationState.Registered
        ) {
            deviceRuntime.remoteConnectivity.start()
        }
        val desiredServiceMode = deviceRuntime.monitoringSettings.load().enabled
        if (desiredServiceMode && !monitoringServiceMode) {
            enterMonitoringServiceMode()
            return
        }
        if (!desiredServiceMode && monitoringServiceMode) {
            leaveMonitoringServiceMode()
            return
        }
        if (monitoringServiceMode) {
            renderMonitoringServiceMode()
            if (desiredServiceMode) requestNotificationPermissionThenStart()
            return
        }
        if (!::cameraPreviewController.isInitialized || permissionRequestInFlight) return

        val latestSettings = settingsRepository.load()
        if (latestSettings != appliedSettings && canOpenSettings(recordingEngine.state.value)) {
            if (latestSettings.videoQuality != appliedSettings.videoQuality) {
                engineUiSettings = engineUiSettings.copy(
                    profileId = latestSettings.videoQuality.toProfileId(),
                )
                engineSettingsRepository.save(engineUiSettings)
            }
            configureCamera(latestSettings)
        }

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCameraPreview()
            if (cameraActive) startMotionMonitoringIfEnabled()
        } else if (permissionWasRequested(KEY_CAMERA_PERMISSION_REQUESTED)) {
            showCameraPermissionDenied()
        }
    }

    private fun applyResponsiveLayoutResources() {
        binding.recordingResult.visibility = if (resources.getBoolean(R.bool.show_recording_result)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val padding = resources.getDimensionPixelSize(R.dimen.recording_panel_padding)
        binding.recordingPanel.setContentPadding(padding, padding, padding, padding)
        binding.recordingPanel.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.recording_panel_bottom_margin)
        }
        val itemSpacing = resources.getDimensionPixelSize(R.dimen.recording_panel_item_spacing)
        binding.recordingResult.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = itemSpacing
        }
    }

    override fun onStop() {
        if (!monitoringServiceMode && ::motionEngine.isInitialized) {
            lifecycleScope.launch { motionEngine.stop() }
        }
        if (!monitoringServiceMode && ::recordingEngine.isInitialized &&
            recordingEngine.state.value.isRecordingSessionActive()
        ) {
            lifecycleScope.launch { recordingEngine.stop(StopReason.LIFECYCLE) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        audioPermissionDialog?.dismiss()
        zoomApplyJob?.cancel()
        detachActivityCameraControlHardware()
        serviceStateJob?.cancel()
        livePreviewJob?.cancel()
        realtimeStatusJob?.cancel()
        liveStatusJob?.cancel()
        timeValidationJob?.cancel()
        registrationStateJob?.cancel()
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=activity_destroy provider=${binding.previewView.surfaceProvider.identity()}"
        }
        deviceRuntime.livePreview.detachSurfaceProvider(binding.previewView.surfaceProvider)
        releaseActivityCameraHost()
        if (::motionToolbarButtonController.isInitialized) motionToolbarButtonController.release()
        if (::binding.isInitialized) {
            binding.previewView.keepScreenOn = false
        }
        super.onDestroy()
    }

    private fun startCameraPreview() {
        if (monitoringServiceMode || deviceRuntime.monitoringSettings.load().enabled ||
            !activityCameraHost || cameraStarting || cameraActive
        ) return

        cameraStarting = true
        publishCameraState(DeviceCameraState.STARTING)
        showCameraStatus(R.string.camera_starting)
        cameraPreviewController.start(
            onPreviewReady = {
                cameraStarting = false
                cameraActive = true
                publishCameraState(DeviceCameraState.READY)
                motionConfigurationInProgress = false
                binding.previewView.keepScreenOn = true
                binding.statusPanel.visibility = View.GONE
                startMotionMonitoringIfEnabled()
                renderRecordingState(recordingEngine.state.value)
            },
            onFailure = { failure ->
                Log.e(TAG, "Camera preview initialization failed", failure)
                cameraStarting = false
                cameraActive = false
                publishCameraState(DeviceCameraState.ERROR)
                motionConfigurationInProgress = false
                binding.previewView.keepScreenOn = false
                renderRecordingState(recordingEngine.state.value)
                showCameraStatus(R.string.camera_initialization_failed, R.string.camera_retry) {
                    startCameraPreview()
                }
            },
        )
    }

    private fun handlePairingIntent(intent: Intent?) {
        val payload = intent?.dataString?.takeIf { it.startsWith("sentricam://pair", ignoreCase = true) }
            ?: return
        intent.data = null
        completePairing(payload)
    }

    private fun completePairing(payload: String) {
        lifecycleScope.launch {
            val result = deviceRuntime.registration.pairNow(payload)
            if (result is RegistrationState.Registered) {
                deviceRuntime.firstRun.pairingSucceeded(hasPermission(Manifest.permission.CAMERA))
                enterMonitoringServiceMode()
            }
            Toast.makeText(
                this@MainActivity,
                if (result is RegistrationState.Registered) {
                    R.string.hub_pairing_complete
                } else {
                    R.string.hub_pairing_failed
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun renderFirstRun(state: RegistrationState, mode: CameraOperatingMode) {
        val standalone = mode is CameraOperatingMode.Standalone
        binding.firstRunPanel.visibility = if (standalone) View.VISIBLE else View.GONE
        if (!standalone) return
        val busy = state is RegistrationState.Configuring || state is RegistrationState.Registering
        binding.firstRunScan.isEnabled = !busy
        binding.firstRunManual.isEnabled = !busy
        binding.firstRunMessage.setText(
            when (state) {
                is RegistrationState.Configuring,
                is RegistrationState.Registering,
                -> R.string.first_run_pairing_in_progress
                is RegistrationState.ConnectionFailed -> R.string.first_run_hub_unreachable
                is RegistrationState.ServerRejected,
                is RegistrationState.InvalidConfiguration,
                is RegistrationState.Error,
                -> R.string.first_run_pairing_failed
                else -> R.string.first_run_pairing_message
            },
        )
        binding.firstRunPanel.bringToFront()
    }

    private fun renderTimeValidation(validation: DeviceTimeValidation) {
        val connectedOrPaired = deviceRuntime.registration.state.value is RegistrationState.Registered
        val action = validation.action()
        val visible = connectedOrPaired && (
            validation.kind == DeviceTimeValidationKind.CLOCK_DRIFT ||
                validation.kind == DeviceTimeValidationKind.TIMEZONE_MISMATCH ||
                validation.verificationFailureReason != null ||
                validation.isVerifying
            )
        binding.timeValidationPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        binding.timeValidationMessage.setText(
            when {
                validation.isVerifying -> R.string.time_validation_verifying
                else -> when (validation.kind) {
                DeviceTimeValidationKind.CLOCK_DRIFT -> R.string.time_validation_clock_drift
                DeviceTimeValidationKind.TIMEZONE_MISMATCH -> R.string.time_validation_timezone_mismatch
                DeviceTimeValidationKind.UNABLE_TO_VALIDATE -> R.string.time_validation_unable
                DeviceTimeValidationKind.VALID -> return
                }
            },
        )
        binding.timeValidationPanel.strokeColor = ContextCompat.getColor(
            this,
            if (action == DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS) {
                R.color.recording_stopping
            } else {
                R.color.camera_toolbar_stroke
            },
        )
        binding.timeValidationAction.isEnabled = !validation.isVerifying
        binding.timeValidationAction.visibility = if (action == DeviceTimeValidationAction.NONE) {
            View.GONE
        } else {
            View.VISIBLE
        }
        when (action) {
            DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS -> {
                binding.timeValidationAction.setIconResource(R.drawable.ic_date_time_24)
                binding.timeValidationAction.contentDescription = getString(R.string.time_validation_open_settings)
                binding.timeValidationAction.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                }
            }
            DeviceTimeValidationAction.RETRY -> {
                binding.timeValidationAction.setIconResource(R.drawable.ic_refresh_24)
                binding.timeValidationAction.contentDescription = getString(R.string.time_validation_retry)
                binding.timeValidationAction.setOnClickListener { deviceRuntime.retryTimeValidation() }
            }
            DeviceTimeValidationAction.NONE -> binding.timeValidationAction.setOnClickListener(null)
        }
        binding.timeValidationPanel.bringToFront()
    }

    private fun handleRecordingToggle() {
        if (monitoringServiceMode) {
            handleServiceRecordingToggle()
            return
        }
        when (recordingEngine.state.value) {
            RecordingState.Idle,
            is RecordingState.Completed,
            is RecordingState.Failed,
            -> beginRecordingWithAudioPolicy()

            is RecordingState.Ready -> {
                val ready = recordingEngine.state.value as RecordingState.Ready
                lifecycleScope.launch {
                    when (recordingEngine.start()) {
                        StartResult.Accepted -> if (
                            ready.session.request.triggerContext.startReason == RecordingStartReason.MANUAL
                        ) {
                            recordingStartFeedbackController.onEvent(
                                RecordingStartFeedbackEvent.MANUAL_STARTED,
                            )
                        }
                        is StartResult.Rejected -> recordingStartFeedbackController.onEvent(
                            RecordingStartFeedbackEvent.MANUAL_REJECTED,
                        )
                        StartResult.AlreadyActive -> Unit
                    }
                }
            }
            is RecordingState.Recording,
            is RecordingState.RotatingSegment,
            -> lifecycleScope.launch {
                if (!motionRecordingCoordinator.promoteMotionRecordingToManual()) {
                    recordingEngine.stop(StopReason.USER)
                } else {
                    recordingStartFeedbackController.onEvent(
                        RecordingStartFeedbackEvent.MOTION_PROMOTED_TO_MANUAL,
                    )
                    binding.recordingResult.setText(R.string.motion_recording_promoted_manual)
                    renderRecordingState(recordingEngine.state.value)
                }
            }

            is RecordingState.Preparing,
            is RecordingState.Starting,
            is RecordingState.Stopping,
            -> Unit
        }
    }

    private fun beginRecordingWithAudioPolicy() {
        if ((!cameraActive && !monitoringServiceMode) || audioPermissionDecisionInFlight) return

        when {
            !appliedSettings.audioEnabled -> {
                binding.recordingResult.setText(R.string.recording_without_audio)
                startRecording(audioPermissionGranted = false)
            }

            hasPermission(Manifest.permission.RECORD_AUDIO) -> startRecording(audioPermissionGranted = true)
            microphonePermissionPermanentlyDenied() -> {
                binding.recordingResult.setText(R.string.microphone_permission_denied_silent)
                startRecording(audioPermissionGranted = false)
            }

            else -> showAudioPermissionExplanation()
        }
    }

    private fun showAudioPermissionExplanation() {
        audioPermissionDecisionInFlight = true
        audioPermissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.microphone_permission_title)
            .setMessage(R.string.microphone_permission_explanation)
            .setPositiveButton(R.string.microphone_allow) { _, _ ->
                permissionPreferences.edit()
                    .putBoolean(KEY_MICROPHONE_PERMISSION_REQUESTED, true)
                    .apply()
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.record_without_audio) { _, _ ->
                audioPermissionDecisionInFlight = false
                binding.recordingResult.setText(R.string.recording_without_audio)
                startRecording(audioPermissionGranted = false)
            }
            .setOnCancelListener {
                audioPermissionDecisionInFlight = false
                renderRecordingState(recordingEngine.state.value)
            }
            .create()
        audioPermissionDialog?.setOnDismissListener { audioPermissionDialog = null }
        audioPermissionDialog?.show()
    }

    private fun startRecording(audioPermissionGranted: Boolean) {
        if (monitoringServiceMode) {
            lifecycleScope.launch {
                val originBeforeCommand = deviceRuntime.device.snapshot.value.recordingOrigin
                val result = deviceRuntime.commandHandler.handle(
                    DeviceCommand.StartRecording(RecordingOrigin.MANUAL),
                )
                when {
                    result is DeviceCommandResult.Accepted && originBeforeCommand != RecordingOrigin.MOTION ->
                        recordingStartFeedbackController.onEvent(
                            RecordingStartFeedbackEvent.MANUAL_STARTED,
                        )
                    result !is DeviceCommandResult.Accepted &&
                        result !is DeviceCommandResult.AlreadyApplied -> {
                        recordingStartFeedbackController.onEvent(
                            RecordingStartFeedbackEvent.MANUAL_REJECTED,
                        )
                        binding.recordingResult.setText(R.string.recording_start_failed)
                    }
                }
            }
            return
        }
        if (!cameraActive) {
            renderRecordingState(recordingEngine.state.value)
            return
        }
        val request = RecordingRequest(
            profile = engineUiSettings.toProfile(appliedSettings.audioEnabled),
            lens = appliedSettings.camera.toRecordingLens(),
            orientationDegrees = cameraPreviewController.currentOutputRotationDegrees(),
            audioPermissionGranted = audioPermissionGranted,
            timestampOverlayEnabled = appliedSettings.overlay.showDateTime,
        )
        lifecycleScope.launch {
            when (motionRecordingCoordinator.startManual(request)) {
                ManualRecordingRequestResult.Started -> recordingStartFeedbackController.onEvent(
                    RecordingStartFeedbackEvent.MANUAL_STARTED,
                )
                ManualRecordingRequestResult.PromotedExistingMotionRecording -> {
                    recordingStartFeedbackController.onEvent(
                        RecordingStartFeedbackEvent.MOTION_PROMOTED_TO_MANUAL,
                    )
                    binding.recordingResult.setText(R.string.motion_recording_promoted_manual)
                }
                ManualRecordingRequestResult.Busy -> Unit
                ManualRecordingRequestResult.Rejected -> {
                    recordingStartFeedbackController.onEvent(
                        RecordingStartFeedbackEvent.MANUAL_REJECTED,
                    )
                    binding.recordingResult.setText(R.string.recording_start_failed)
                    Log.w(TAG, "Manual recording request was rejected by the coordinator")
                }
            }
        }
    }

    private fun renderRecordingState(state: RecordingState) {
        binding.recordingEngineStatus.render(RecordingEngineStatusUiFactory.from(state))
        renderMotionState(if (::motionEngine.isInitialized) motionEngine.state.value else MotionDetectionState.Disabled)
        when (state) {
            RecordingState.Idle -> {
                renderDurations(0L, 0L)
                setRecordingControls(
                    status = if (cameraActive) R.string.recording_ready else R.string.recording_camera_not_ready,
                    statusColor = if (cameraActive) {
                        R.color.vision_status_healthy
                    } else {
                        R.color.vision_status_offline
                    },
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = cameraActive && !audioPermissionDecisionInFlight,
                )
            }

            is RecordingState.Preparing -> {
                renderDurations(0L, 0L)
                setRecordingControls(
                    status = R.string.recording_preparing,
                    statusColor = R.color.vision_brand_primary,
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = false,
                )
            }

            is RecordingState.Ready -> {
                renderDurations(0L, 0L)
                setRecordingControls(
                    status = R.string.recording_ready,
                    statusColor = R.color.vision_status_healthy,
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = cameraActive,
                )
            }

            is RecordingState.Starting -> {
                renderDurations(state.session.segments.sumOf { it.durationMillis }, 0L)
                setRecordingControls(
                    status = R.string.recording_starting,
                    statusColor = R.color.vision_brand_primary,
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = false,
                )
            }

            is RecordingState.Recording -> {
                renderDurations(state.sessionDurationMillis, state.segmentDurationMillis)
                setRecordingControls(
                    status = when (state.storageLevel) {
                        RecordingStorageLevel.HEALTHY -> if (
                            state.session.request.triggerContext.startReason == RecordingStartReason.MOTION &&
                                !state.session.request.triggerContext.manualControlClaimed
                        ) R.string.recording_active_motion else R.string.recording_active
                        RecordingStorageLevel.WARNING -> R.string.recording_storage_warning
                        RecordingStorageLevel.CRITICAL -> R.string.recording_storage_critical
                    },
                    statusColor = when {
                        state.storageLevel != RecordingStorageLevel.HEALTHY ->
                            R.color.vision_status_motion
                        state.session.request.triggerContext.startReason == RecordingStartReason.MOTION &&
                            !state.session.request.triggerContext.manualControlClaimed ->
                            R.color.vision_status_motion
                        else -> R.color.vision_status_recording
                    },
                    buttonText = R.string.recording_stop,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = true,
                )
            }

            is RecordingState.RotatingSegment -> {
                renderDurations(state.sessionDurationMillis, state.segmentDurationMillis)
                setRecordingControls(
                    status = R.string.recording_rotating_segment,
                    statusColor = if (
                        state.session.request.triggerContext.startReason == RecordingStartReason.MOTION &&
                        !state.session.request.triggerContext.manualControlClaimed
                    ) {
                        R.color.vision_status_motion
                    } else {
                        R.color.vision_status_recording
                    },
                    buttonText = R.string.recording_stop,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = true,
                )
            }

            is RecordingState.Stopping -> {
                setRecordingControls(
                    status = R.string.recording_stopping,
                    statusColor = R.color.vision_status_motion,
                    buttonText = R.string.recording_stop,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = false,
                )
            }

            is RecordingState.Completed -> {
                renderDurations(0L, 0L)
                binding.recordingResult.text = if (state.result.successfulSegments.isEmpty()) {
                    getString(R.string.recording_finalization_failed)
                } else {
                    ""
                }
                setRecordingControls(
                    status = R.string.recording_saved,
                    statusColor = R.color.vision_status_healthy,
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = cameraActive,
                )
            }

            is RecordingState.Failed -> {
                renderDurations(0L, 0L)
                val message = recordingErrorMessage(state.failure)
                binding.recordingResult.setText(message)
                Log.e(
                    TAG,
                    "Recording engine failed: ${state.failure.code.stableCode}/${state.failure.diagnosticTag}",
                    state.failure.technicalCause,
                )
                setRecordingControls(
                    status = message,
                    statusColor = R.color.vision_status_critical,
                    buttonText = R.string.recording_start,
                    buttonColor = R.color.vision_status_recording,
                    buttonEnabled = cameraActive,
                )
            }
        }
        binding.settingsButton.isEnabled = canOpenSettings(state) && !audioPermissionDecisionInFlight
        binding.recordingsButton.isEnabled = canOpenSettings(state) && !audioPermissionDecisionInFlight
        binding.languageButton.isEnabled = canOpenSettings(state) && !audioPermissionDecisionInFlight
        binding.motionSettingsButton.isEnabled = canOpenSettings(state) &&
            !audioPermissionDecisionInFlight && !motionConfigurationInProgress
        renderCameraSwitchControl(state)
        if (monitoringServiceMode) {
            val running = deviceRuntime.monitoring.state.value.status == MonitoringStatus.RUNNING
            binding.recordingButton.isEnabled = running && state !is RecordingState.Preparing &&
                state !is RecordingState.Starting && state !is RecordingState.Stopping
            binding.cameraSwitchButton.isEnabled = false
        }
    }

    private fun renderMotionState(state: MotionDetectionState) {
        val motionRecordingActive = ::motionRecordingCoordinator.isInitialized &&
            motionRecordingCoordinator.isMotionRecordingActive()
        val ui = MotionStatusUiFactory.from(state, motionRecordingActive)
        binding.motionStatus.render(ui)
        motionToolbarButtonController.render(ui.kind)
    }

    private fun setRecordingControls(
        @StringRes status: Int,
        @ColorRes statusColor: Int,
        @StringRes buttonText: Int,
        @ColorRes buttonColor: Int,
        buttonEnabled: Boolean,
    ) {
        binding.recordingStatus.setText(status)
        val resolvedStatusColor = ContextCompat.getColor(this, statusColor)
        binding.recordingStatus.setTextColor(resolvedStatusColor)
        binding.recordingPanel.strokeColor = resolvedStatusColor
        binding.recordingButton.contentDescription = getString(buttonText)
        binding.recordingButton.isSelected = buttonText == R.string.recording_stop
        binding.recordingButton.imageTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.vision_surface_disabled),
                ContextCompat.getColor(this, buttonColor),
            ),
        )
        binding.recordingButton.isEnabled = buttonEnabled
    }

    private fun showLanguageSelector() {
        if (!canOpenSettings(currentRecordingState())) {
            Toast.makeText(this, R.string.language_change_recording_active, Toast.LENGTH_SHORT).show()
            return
        }
        val languages = arrayOf(AppLanguage.ARABIC, AppLanguage.ENGLISH)
        val labels = arrayOf(
            getString(R.string.language_arabic),
            getString(R.string.language_english),
        )
        val selected = languageController.selectedLanguage() ?: displayedLanguage()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_selector_title)
            .setSingleChoiceItems(labels, languages.indexOf(selected)) { dialog, index ->
                if (!canOpenSettings(currentRecordingState())) {
                    Toast.makeText(
                        this,
                        R.string.language_change_recording_active,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    languageController.selectLanguage(languages[index])
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun displayedLanguage(): AppLanguage {
        val locales = ConfigurationCompat.getLocales(resources.configuration)
        val languageTag = if (locales.isEmpty) null else locales[0]?.toLanguageTag()
        return AppLanguage.fromLanguageTag(languageTag) ?: AppLanguage.ENGLISH
    }

    @StringRes
    private fun recordingErrorMessage(error: RecordingFailure): Int = when (error.code) {
        RecordingFailureCode.CAMERA_UNAVAILABLE -> R.string.camera_initialization_failed
        RecordingFailureCode.PERMISSION_MISSING -> R.string.camera_permission_denied
        RecordingFailureCode.STORAGE_UNAVAILABLE -> R.string.recording_storage_unavailable
        RecordingFailureCode.INSUFFICIENT_STORAGE -> R.string.recording_insufficient_storage
        RecordingFailureCode.AUDIO_PERMISSION_MISSING -> R.string.microphone_permission_denied_silent
        RecordingFailureCode.RECORDER_INITIALIZATION_FAILED,
        RecordingFailureCode.RECORDING_START_FAILED,
        -> R.string.recording_start_failed
        RecordingFailureCode.SEGMENT_FINALIZATION_FAILED -> R.string.recording_finalization_failed
        RecordingFailureCode.UNSUPPORTED_QUALITY -> R.string.recording_unsupported_quality
        RecordingFailureCode.INVALID_REQUEST,
        RecordingFailureCode.UNEXPECTED_FAILURE,
        -> R.string.recording_unexpected_failure
    }

    private fun renderDurations(sessionMillis: Long, segmentMillis: Long) {
        binding.recordingDuration.text = RecordingDurationFormatter.format(sessionMillis)
        binding.recordingSegmentDuration.text = getString(
            R.string.recording_segment_duration,
            RecordingDurationFormatter.format(segmentMillis),
        )
    }

    private fun requestCameraPermission() {
        permissionPreferences.edit().putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, true).apply()
        permissionRequestInFlight = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showCameraPermissionDenied() {
        cameraStarting = false
        cameraActive = false
        publishCameraState(DeviceCameraState.UNAVAILABLE)
        binding.previewView.keepScreenOn = false
        if (::recordingEngine.isInitialized && recordingEngine.state.value.isRecordingSessionActive()) {
            lifecycleScope.launch { recordingEngine.stop(StopReason.CAMERA_UNAVAILABLE) }
        }
        if (::motionEngine.isInitialized) lifecycleScope.launch { motionEngine.stop() }
        if (::cameraPreviewController.isInitialized) cameraPreviewController.stop()
        renderRecordingState(currentRecordingState())

        val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.CAMERA,
        )
        if (permanentlyDenied) {
            showCameraStatus(
                R.string.camera_permission_permanently_denied,
                R.string.camera_open_settings,
                ::openAppSettings,
            )
        } else {
            showCameraStatus(
                R.string.camera_permission_denied,
                R.string.camera_allow,
                ::requestCameraPermission,
            )
        }
    }

    private fun showCameraStatus(
        @StringRes message: Int,
        @StringRes actionLabel: Int? = null,
        action: (() -> Unit)? = null,
    ) {
        binding.statusMessage.setText(message)
        binding.statusPanel.visibility = View.VISIBLE
        binding.statusAction.visibility = if (actionLabel == null) View.GONE else View.VISIBLE
        if (actionLabel != null) binding.statusAction.setText(actionLabel)
        binding.statusAction.setOnClickListener { action?.invoke() }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun openUnifiedSettings(destination: SettingsDestination) {
        if (!canOpenSettings(currentRecordingState())) return
        appSettingsLauncher.launch(AppSettingsActivity.intent(this, destination))
    }

    private fun toggleMotionDetection() {
        if (!canOpenSettings(currentRecordingState()) || audioPermissionDecisionInFlight ||
            motionConfigurationInProgress
        ) return
        if (monitoringServiceMode) {
            lifecycleScope.launch {
                val command = if (motionConfig.enabled) {
                    DeviceCommand.StopMotionDetection
                } else {
                    DeviceCommand.StartMotionDetection
                }
                val result = deviceRuntime.commandHandler.handle(command)
                if (result == DeviceCommandResult.Accepted || result == DeviceCommandResult.AlreadyApplied) {
                    motionConfig = motionSettingsRepository.load()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.monitoring_service_start_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }
        val enable = !motionConfig.enabled
        renderMotionState(
            if (enable) MotionDetectionState.Initializing(0L, motionConfig.warmupFrameCount)
            else MotionDetectionState.Disabled,
        )
        applyMotionSettings(motionConfig.copy(enabled = enable))
        Toast.makeText(
            this,
            if (enable) R.string.motion_enabled_confirmation else R.string.motion_disabled_confirmation,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showMotionSettingsHintOnce() {
        if (!::motionSettingsHintPolicy.isInitialized || !motionSettingsHintPolicy.consumeHint()) return
        Toast.makeText(this, R.string.motion_long_press_hint, Toast.LENGTH_LONG).show()
    }

    private fun applyMotionSettings(config: MotionDetectionConfig) {
        if (!canOpenSettings(currentRecordingState())) return
        motionConfig = MotionSettingsPolicy.normalize(config, BuildConfig.DEBUG)
        motionSettingsRepository.save(motionConfig)
        deviceRuntime.publishMotionConfiguration(motionConfig)
        if (!::motionEngine.isInitialized || !hasPermission(Manifest.permission.CAMERA)) {
            renderMotionState(MotionDetectionState.Disabled)
            return
        }
        motionConfigurationInProgress = true
        lifecycleScope.launch {
            try {
                if (motionConfig.enabled && cameraActive) {
                    motionEngine.start(motionConfig)
                } else {
                    motionEngine.stop()
                }
            } finally {
                motionConfigurationInProgress = false
                renderMotionState(motionEngine.state.value)
            }
        }
    }

    private fun startMotionMonitoringIfEnabled() {
        if (!::motionEngine.isInitialized) return
        if (!motionConfig.enabled) {
            renderMotionState(MotionDetectionState.Disabled)
            return
        }
        lifecycleScope.launch {
            motionEngine.start(motionConfig)
            motionAnalysisFailure?.let { failure ->
                motionEngine.reportAnalyzerFailure(failure, unsupportedCombination = true)
            }
        }
    }

    private fun createMotionRecordingRequest(): RecordingRequest? {
        if (!cameraActive || !motionConfig.enabled) return null
        return RecordingRequest(
            profile = engineUiSettings.toProfile(appliedSettings.audioEnabled),
            lens = appliedSettings.camera.toRecordingLens(),
            orientationDegrees = cameraPreviewController.currentOutputRotationDegrees(),
            audioPermissionGranted = appliedSettings.audioEnabled &&
                hasPermission(Manifest.permission.RECORD_AUDIO),
            timestampOverlayEnabled = appliedSettings.overlay.showDateTime,
        )
    }

    private fun createDeviceRecordingRequest(): RecordingRequest? {
        if (!cameraActive) return null
        return RecordingRequest(
            profile = engineUiSettings.toProfile(appliedSettings.audioEnabled),
            lens = appliedSettings.camera.toRecordingLens(),
            orientationDegrees = cameraPreviewController.currentOutputRotationDegrees(),
            audioPermissionGranted = appliedSettings.audioEnabled &&
                hasPermission(Manifest.permission.RECORD_AUDIO),
            timestampOverlayEnabled = appliedSettings.overlay.showDateTime,
        )
    }

    private fun applyUnifiedSettings() {
        val desiredMonitoring = deviceRuntime.monitoringSettings.load().enabled
        if (desiredMonitoring != monitoringServiceMode) {
            if (desiredMonitoring) enterMonitoringServiceMode() else leaveMonitoringServiceMode()
            return
        }
        if (monitoringServiceMode) {
            val updatedCamera = settingsRepository.load()
            val updatedRecording = engineSettingsRepository.load()
            val updatedMotion = motionSettingsRepository.load()
            val requiresCameraRestart = updatedCamera != appliedSettings || updatedRecording != engineUiSettings
            appliedSettings = updatedCamera
            engineUiSettings = updatedRecording
            motionConfig = updatedMotion
            lifecycleScope.launch {
                deviceRuntime.commandHandler.handle(DeviceCommand.UpdateMotionConfig(motionConfig))
                if (requiresCameraRestart) deviceRuntime.monitoring.restart()
            }
            renderMonitoringServiceMode()
            return
        }
        if (!canOpenSettings(currentRecordingState())) return
        val updatedCamera = settingsRepository.load()
        val updatedRecording = engineSettingsRepository.load()
        val updatedMotion = motionSettingsRepository.load()
        val cameraChanged = updatedCamera != appliedSettings
        val recordingChanged = updatedRecording != engineUiSettings
        val previousMotion = motionConfig
        val motionApplyMode = MotionLiveConfigPolicy.mode(previousMotion, updatedMotion)

        engineUiSettings = updatedRecording
        motionConfig = updatedMotion
        deviceRuntime.publishMotionConfiguration(updatedMotion)
        if (cameraChanged || recordingChanged) {
            configureCamera(updatedCamera)
            if (hasPermission(Manifest.permission.CAMERA)) startCameraPreview()
            return
        }

        appliedSettings = updatedCamera
        if (motionApplyMode == MotionConfigApplyMode.LIVE_UPDATE && ::motionEngine.isInitialized) {
            lifecycleScope.launch {
                when {
                    !updatedMotion.enabled -> {
                        motionEngine.stop()
                    }
                    !previousMotion.enabled -> startMotionMonitoringIfEnabled()
                    else -> motionEngine.updateConfig(updatedMotion)
                }
            }
        }
    }

    private fun openRecordingsLibrary() {
        if (!canOpenSettings(currentRecordingState())) return
        startActivity(Intent(this, RecordingsActivity::class.java))
    }

    private fun enterMonitoringServiceMode() {
        if (monitoringServiceMode && !activityCameraHost) {
            requestNotificationPermissionThenStart()
            return
        }
        monitoringServiceMode = true
        releaseActivityCameraHost()
        observeMonitoringServiceState()
        renderMonitoringServiceMode()
        if (hasPermission(Manifest.permission.CAMERA)) {
            requestNotificationPermissionThenStart()
        } else {
            requestCameraPermission()
        }
    }

    private fun leaveMonitoringServiceMode() {
        renderMonitoringServiceMode()
        lifecycleScope.launch {
            deviceRuntime.monitoring.stop()
            deviceRuntime.cameraOwnership.owner.first { it == CameraOwner.NONE }
            monitoringServiceMode = false
            serviceStateJob?.cancel()
            serviceStateJob = null
            configureCamera(settingsRepository.load())
            if (hasPermission(Manifest.permission.CAMERA)) startCameraPreview()
        }
    }

    private fun requestNotificationPermissionThenStart() {
        if (!monitoringServiceMode || !deviceRuntime.monitoringSettings.load().enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            !permissionWasRequested(KEY_NOTIFICATION_PERMISSION_REQUESTED)
        ) {
            permissionPreferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        if (!monitoringServiceMode || !deviceRuntime.monitoringSettings.load().enabled) return
        lifecycleScope.launch {
            val settings = deviceRuntime.monitoringSettings.load()
            val result = deviceRuntime.monitoring.start(
                MonitoringConfiguration(
                    motionDetectionEnabled = motionConfig.enabled,
                    recordingOnMotionEnabled = motionConfig.enabled,
                    audioEnabled = appliedSettings.audioEnabled,
                ),
                MonitoringPolicy(
                    stopWhenAppLeavesForeground = false,
                    allowForegroundService = true,
                    restartAfterUnexpectedStop = settings.autoStart || settings.restartOnFailure,
                ),
            )
            if (result is com.ashraffarag.sentricam.monitoring.domain.MonitoringTransitionResult.Rejected) {
                deviceRuntime.firstRun.monitoringStartFailed(FirstRunCoordinator.ACTION_MONITORING_START)
                Toast.makeText(
                    this@MainActivity,
                    R.string.monitoring_service_start_failed,
                    Toast.LENGTH_LONG,
                ).show()
                showCameraStatus(
                    R.string.monitoring_action_required,
                    R.string.camera_retry,
                ) { startMonitoringService() }
            } else {
                deviceRuntime.firstRun.monitoringStarted()
            }
        }
    }

    private fun restartMonitoringForMicrophoneThenRecord() {
        lifecycleScope.launch {
            if (!monitoringServiceMode || !deviceRuntime.monitoringSettings.load().enabled) return@launch
            val restart = deviceRuntime.monitoring.restart()
            if (restart is MonitoringTransitionResult.Rejected) {
                binding.recordingResult.setText(R.string.recording_start_failed)
                return@launch
            }
            val ready = withTimeoutOrNull(MONITORING_RESTART_TIMEOUT_MILLIS) {
                deviceRuntime.monitoring.state.first { state ->
                    state.status == MonitoringStatus.RUNNING ||
                        state.status == MonitoringStatus.ERROR ||
                        state.status == MonitoringStatus.STOPPED
                }
            }
            if (ready?.status == MonitoringStatus.RUNNING) {
                startRecording(audioPermissionGranted = true)
            } else {
                binding.recordingResult.setText(R.string.recording_start_failed)
            }
        }
    }

    private fun observeMonitoringServiceState() {
        serviceStateJob?.cancel()
        serviceStateJob = lifecycleScope.launch {
            deviceRuntime.device.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    motionConfig = it.motionConfiguration
                    renderMonitoringServiceMode()
                }
        }
    }

    private fun renderMonitoringServiceMode() {
        if (!monitoringServiceMode) return
        val state = deviceRuntime.device.state.value
        cameraActive = false
        cameraStarting = false
        binding.previewView.keepScreenOn = false
        binding.cameraSwitchButton.isEnabled = false
        renderLivePreviewVisibility(deviceRuntime.livePreview.presentation.value)
        renderRecordingState(state.recording)
        if (state.monitoring.status == MonitoringStatus.RUNNING && state.recording == RecordingState.Idle) {
            binding.recordingStatus.setText(R.string.recording_ready)
            binding.recordingStatus.setTextColor(ContextCompat.getColor(this, R.color.recording_ready))
        }
        renderMotionState(state.motion)
    }

    private fun renderLivePreviewVisibility(presentation: String) {
        val visible = presentation != "hidden"
        binding.previewView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.previewView.alpha = if (presentation == "dimmed") 0.28f else 1f
        binding.previewTimestampOverlay.setTimestampVisible(
            visible && appliedSettings.overlay.showDateTime,
        )
        if (!monitoringServiceMode) return

        when {
            deviceRuntime.device.state.value.monitoring.status == MonitoringStatus.ERROR ->
                showCameraStatus(R.string.monitoring_service_start_failed)
            deviceRuntime.device.state.value.monitoring.status != MonitoringStatus.RUNNING ->
                showCameraStatus(R.string.monitoring_service_active)
            !visible -> showCameraStatus(R.string.live_preview_hidden)
            else -> binding.statusPanel.visibility = View.GONE
        }
    }

    private fun Any?.identity(): String =
        this?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"

    private fun handleServiceRecordingToggle() {
        val recording = deviceRuntime.device.state.value.recording
        val origin = deviceRuntime.device.snapshot.value.recordingOrigin
        when (recording) {
            is RecordingState.Recording,
            is RecordingState.RotatingSegment,
            -> lifecycleScope.launch {
                val command = if (origin == RecordingOrigin.MOTION) {
                    DeviceCommand.StartRecording(RecordingOrigin.MANUAL)
                } else {
                    DeviceCommand.StopRecording(RecordingOrigin.MANUAL)
                }
                deviceRuntime.commandHandler.handle(command)
            }
            is RecordingState.Preparing,
            is RecordingState.Ready,
            is RecordingState.Starting,
            is RecordingState.Stopping,
            -> Unit
            else -> beginRecordingWithAudioPolicy()
        }
    }

    private fun releaseActivityCameraHost() {
        if (!activityCameraHost) return
        cameraConfigurationGeneration++
        recordingStateJob?.cancel()
        recordingStateJob = null
        motionStateJob?.cancel()
        motionStateJob = null
        motionCoordinatorStateJob?.cancel()
        motionCoordinatorStateJob = null
        if (::motionRecordingCoordinator.isInitialized) motionRecordingCoordinator.release()
        if (::cameraPreviewController.isInitialized) cameraPreviewController.stop()
        detachActivityCameraControlHardware()
        overlayEngine?.close()
        overlayEngine = null
        if (deviceRuntime.cameraOwnership.owner.value == CameraOwner.CAMERA_ACTIVITY) {
            deviceRuntime.detachCommandPorts()
            deviceRuntime.detachMotionState()
            deviceRuntime.detachRecordingState()
        }
        if (::motionEngine.isInitialized) {
            val engine = motionEngine
            lifecycleScope.launch { engine.release() }
        }
        deviceRuntime.detachLiveCameraStream()
        if (::recordingEngine.isInitialized) {
            val engine = recordingEngine
            lifecycleScope.launch { engine.release() }
        }
        deviceRuntime.cameraOwnership.release(CameraOwner.CAMERA_ACTIVITY, cameraOwnerToken)
        activityCameraHost = false
        cameraActive = false
        cameraStarting = false
    }

    private fun currentRecordingState(): RecordingState =
        if (monitoringServiceMode || !::recordingEngine.isInitialized) {
            deviceRuntime.device.state.value.recording
        } else {
            recordingEngine.state.value
        }

    private fun configureCamera(settings: RecordingSettings) {
        if (deviceRuntime.monitoringSettings.load().enabled) {
            monitoringServiceMode = true
            observeMonitoringServiceState()
            renderMonitoringServiceMode()
            return
        }
        when (deviceRuntime.cameraOwnership.acquire(CameraOwner.CAMERA_ACTIVITY, cameraOwnerToken)) {
            CameraOwnershipResult.Acquired,
            CameraOwnershipResult.AlreadyOwned,
            -> activityCameraHost = true
            is CameraOwnershipResult.Rejected -> {
                monitoringServiceMode = true
                observeMonitoringServiceState()
                renderMonitoringServiceMode()
                return
            }
        }
        val configurationGeneration = ++cameraConfigurationGeneration
        recordingStateJob?.cancel()
        motionStateJob?.cancel()
        motionCoordinatorStateJob?.cancel()
        deviceRuntime.detachCommandPorts()
        if (::motionRecordingCoordinator.isInitialized) motionRecordingCoordinator.release()
        if (::motionEngine.isInitialized) {
            val previousMotionEngine = motionEngine
            lifecycleScope.launch { previousMotionEngine.release() }
        }
        if (::recordingEngine.isInitialized) {
            val previousEngine = recordingEngine
            lifecycleScope.launch { previousEngine.release() }
        }
        if (::cameraPreviewController.isInitialized) cameraPreviewController.stop()
        detachActivityCameraControlHardware()
        overlayEngine?.close()

        cameraStarting = false
        cameraActive = false
        binding.previewView.keepScreenOn = false
        binding.previewView.visibility = View.VISIBLE
        binding.previewTimestampOverlay.visibility = View.VISIBLE
        motionAnalysisFailure = null
        appliedSettings = settings
        lensSwitchController = CameraLensSwitchController(settings.camera.toLensFacing())
        publishCameraState(DeviceCameraState.UNAVAILABLE)
        binding.previewTimestampOverlay.setConfiguration(settings.overlay)

        val cameraXRecordingEngine = CameraXRecordingEngine(
            context = this,
            profileId = engineUiSettings.profileId,
            simulateStorageWarning = BuildConfig.DEBUG && engineUiSettings.simulateStorageWarning,
            onFinalizedSegment = deviceRuntime::recordingSegmentFinalized,
        )
        val cameraXMotionEngine = CameraXMotionDetectionEngine()
        val newOverlayEngine = if (settings.overlay.showDateTime) {
            CameraXRecordingOverlayEngine(settings.overlay)
        } else {
            null
        }
        overlayEngine = newOverlayEngine
        recordingEngine = cameraXRecordingEngine
        deviceRuntime.attachRecordingState(cameraXRecordingEngine.state)
        motionEngine = cameraXMotionEngine
        deviceRuntime.attachLiveCameraStream(cameraXMotionEngine.liveStreamController)
        deviceRuntime.attachMotionState(cameraXMotionEngine.state, motionConfig)
        motionRecordingCoordinator = MotionRecordingCoordinator(
            motionEngine = cameraXMotionEngine,
            recordingEngine = cameraXRecordingEngine,
            requestProvider = MotionRecordingRequestProvider { _ -> createMotionRecordingRequest() },
            nowMillis = System::currentTimeMillis,
            scope = lifecycleScope,
        ).also(MotionRecordingCoordinator::start)
        deviceRuntime.attachCommandPorts(
            recording = RecordingEngineCommandPort(
                engine = cameraXRecordingEngine,
                requestProvider = { _: RecordingOrigin -> createDeviceRecordingRequest() },
            ),
            motion = MotionEngineCommandPort(
                engine = cameraXMotionEngine,
                repository = motionSettingsRepository,
                onConfigurationChanged = { updated ->
                    motionConfig = updated
                    deviceRuntime.publishMotionConfiguration(updated)
                },
            ),
            recordingSettings = AppRecordingSettingsCommandPort(
                engineSettings = engineSettingsRepository,
                recordingSettings = settingsRepository,
            ),
        )
        cameraPreviewController = CameraXPreviewController(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            videoCapture = cameraXRecordingEngine.videoCapture,
            initialLens = lensSwitchController.state.selectedLens,
            cameraEffect = newOverlayEngine?.cameraEffect,
            imageAnalysis = cameraXMotionEngine.imageAnalysis.useCase,
            onAnalysisUnavailable = { failure ->
                motionAnalysisFailure = failure
            },
            onTargetRotationChanged = cameraXMotionEngine::resetForCameraChange,
            onCameraBound = { camera ->
                cameraXRecordingEngine.onCameraBound(camera.cameraInfo)
                cameraXMotionEngine.resetForCameraChange()
                attachActivityCameraControlHardware(camera)
            },
        )
        recordingStateJob = lifecycleScope.launch {
            cameraXRecordingEngine.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect(::renderRecordingState)
        }
        motionStateJob = lifecycleScope.launch {
            cameraXMotionEngine.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect(::renderMotionState)
        }
        motionCoordinatorStateJob = lifecycleScope.launch {
            motionRecordingCoordinator.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { state ->
                    if (state is MotionRecordingCoordinatorState.TriggerRejected) {
                        binding.recordingResult.setText(R.string.motion_error_recording_rejected)
                    }
                    renderMotionState(cameraXMotionEngine.state.value)
                }
        }
        cameraPreviewController.queryAvailableLenses(
            onResult = { lenses ->
                if (configurationGeneration != cameraConfigurationGeneration) return@queryAvailableLenses
                lensSwitchController.updateAvailableLenses(lenses)
                publishCameraState(
                    if (cameraActive) DeviceCameraState.READY else DeviceCameraState.UNAVAILABLE,
                    lenses.size,
                )
                renderCameraSwitchControl(recordingEngine.state.value)
            },
            onFailure = { failure ->
                if (configurationGeneration != cameraConfigurationGeneration) return@queryAvailableLenses
                Log.w(TAG, "Unable to query camera lens availability", failure)
                lensSwitchController.updateAvailableLenses(emptySet())
                publishCameraState(DeviceCameraState.ERROR)
                renderCameraSwitchControl(recordingEngine.state.value)
            },
        )
    }

    private fun requestCameraSwitch() {
        val recordingBusy = !canOpenSettings(recordingEngine.state.value)
        val targetLens = lensSwitchController.requestSwitch(cameraActive, recordingBusy) ?: return

        cameraStarting = true
        cameraActive = false
        publishCameraState(DeviceCameraState.SWITCHING)
        if (::motionEngine.isInitialized) motionEngine.resetForCameraChange()
        renderRecordingState(recordingEngine.state.value)
        showCameraStatus(R.string.camera_switching)
        val accepted = cameraPreviewController.switchLens(
            lens = targetLens,
            onPreviewReady = {
                lensSwitchController.completeSwitch()
                cameraStarting = false
                cameraActive = true
                appliedSettings = appliedSettings.copy(camera = targetLens.toSettingsCamera())
                settingsRepository.save(appliedSettings)
                binding.previewView.keepScreenOn = true
                binding.statusPanel.visibility = View.GONE
                publishCameraState(DeviceCameraState.READY)
                renderRecordingState(recordingEngine.state.value)
            },
            onFailure = { failure ->
                Log.e(TAG, "Unable to switch camera lens", failure)
                lensSwitchController.cancelSwitch()
                cameraStarting = false
                cameraActive = false
                publishCameraState(DeviceCameraState.ERROR)
                binding.previewView.keepScreenOn = false
                renderRecordingState(recordingEngine.state.value)
                showCameraStatus(R.string.camera_switch_failed, R.string.camera_retry) {
                    startCameraPreview()
                }
            },
        )
        if (!accepted) {
            lensSwitchController.cancelSwitch()
            cameraStarting = false
            cameraActive = true
            publishCameraState(DeviceCameraState.READY)
            binding.statusPanel.visibility = View.GONE
            renderRecordingState(recordingEngine.state.value)
        }
    }

    private fun renderCameraSwitchControl(recordingState: RecordingState) {
        val lensState = lensSwitchController.state
        binding.cameraSwitchButton.visibility = if (lensState.showSwitchControl) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.cameraSwitchButton.isEnabled = lensState.canSwitch(
            cameraReady = cameraActive,
            recordingInProgress = !canOpenSettings(recordingState),
        ) && !audioPermissionDecisionInFlight
        binding.cameraSwitchButton.contentDescription = getString(
            when (lensState.selectedLens) {
                CameraLensFacing.REAR -> R.string.camera_switch_to_front
                CameraLensFacing.FRONT -> R.string.camera_switch_to_rear
            },
        )
        renderLiveCameraControls()
    }

    private fun attachActivityCameraControlHardware(camera: androidx.camera.core.Camera) {
        detachActivityCameraControlHardware()
        activityCameraControlHardware = AndroidCameraXControl(
            camera = camera,
            preview = deviceRuntime.livePreview,
            requestReconfigure = deviceRuntime::requestCameraReconfigure,
            logger = AndroidCameraControlLogger(),
        ).also(deviceRuntime::attachCameraControlHardware)
        renderLiveCameraControls()
    }

    private fun detachActivityCameraControlHardware() {
        activityCameraControlHardware?.let(deviceRuntime::detachCameraControlHardware)
        activityCameraControlHardware = null
        if (::binding.isInitialized) binding.cameraControlRail.visibility = View.GONE
    }

    private fun renderLiveCameraControls() {
        if (!deviceRuntime.cameraControlHardware.attached()) {
            binding.cameraControlRail.visibility = View.GONE
            return
        }
        val report = deviceRuntime.cameraControlState()
        val zoom = report.capabilities.firstOrNull { it.id == CameraControlIds.ZOOM }
        val flash = report.capabilities.firstOrNull { it.id == CameraControlIds.TORCH }
        val zoomAvailable = zoom?.supported == true && zoom.writable
        val flashAvailable = flash?.supported == true && flash.writable
        binding.cameraControlRail.visibility = if (zoomAvailable || flashAvailable) View.VISIBLE else View.GONE

        binding.zoomSlider.isEnabled = zoomAvailable
        if (zoomAvailable) {
            val minimum = zoom.minimum ?: 1.0
            val maximum = zoom.maximum ?: minimum
            if (maximum > minimum) {
                binding.zoomSlider.valueFrom = minimum.toFloat()
                binding.zoomSlider.valueTo = maximum.toFloat()
                val current = report.settings.zoom.coerceIn(minimum, maximum).toFloat()
                binding.zoomSlider.value = current
                binding.zoomValue.text = getString(R.string.camera_zoom_value, current)
                binding.zoomSlider.contentDescription = getString(R.string.camera_zoom_accessibility, current)
            }
        }

        val flashMode = LiveCameraControlPolicy.flashMode(report.settings)
        binding.flashButton.isEnabled = flashAvailable
        binding.flashButton.setIconResource(
            if (flashMode == LiveFlashMode.ON) R.drawable.ic_flash_on_24 else R.drawable.ic_flash_off_24,
        )
        binding.flashButton.contentDescription = getString(
            if (flashMode == LiveFlashMode.ON) R.string.camera_flash_on else R.string.camera_flash_off,
        )
    }

    private fun scheduleZoom(value: Float) {
        val requestId = zoomRequestPresentation.beginRequest()
        zoomApplyJob?.cancel()
        zoomApplyJob = lifecycleScope.launch {
            delay(LIVE_ZOOM_DEBOUNCE_MILLIS)
            val descriptor = deviceRuntime.cameraControlState().capabilities
                .firstOrNull { it.id == CameraControlIds.ZOOM } ?: return@launch
            val minimum = descriptor.minimum ?: return@launch
            val maximum = descriptor.maximum ?: return@launch
            val desired = deviceRuntime.cameraControlSettings().copy(
                zoom = LiveCameraControlPolicy.normalizedZoom(value, minimum, maximum),
            )
            val result = deviceRuntime.applyLocalCameraControl(CameraControlIds.ZOOM, desired)
            when (zoomRequestPresentation.resolve(requestId, result)) {
                ZoomRequestPresentation.APPLIED -> renderLiveCameraControls()
                ZoomRequestPresentation.FAILURE -> {
                    Log.w(TAG, "event=zoom_failed result=${result.code}")
                    Toast.makeText(this@MainActivity, R.string.camera_zoom_failed, Toast.LENGTH_SHORT).show()
                }
                ZoomRequestPresentation.STALE -> Unit
            }
        }
    }

    private fun showFlashSelector() {
        val current = LiveCameraControlPolicy.flashMode(deviceRuntime.cameraControlSettings())
        val modes = arrayOf(LiveFlashMode.OFF, LiveFlashMode.ON, LiveFlashMode.AUTO)
        val labels = arrayOf(
            getString(R.string.camera_flash_off),
            getString(R.string.camera_flash_on),
            getString(R.string.camera_flash_auto_unavailable),
        )
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_single_choice,
            labels,
        ) {
            override fun isEnabled(position: Int): Boolean =
                modes[position] != LiveFlashMode.AUTO || LiveCameraControlPolicy.AUTO_FLASH_AVAILABLE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_flash_selector_title)
            .setSingleChoiceItems(adapter, modes.indexOf(current)) { dialog, index ->
                applyFlashMode(modes[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyFlashMode(mode: LiveFlashMode) {
        val desired = LiveCameraControlPolicy.settingsForFlashMode(
            deviceRuntime.cameraControlSettings(),
            mode,
        )
        if (desired == null) {
            Log.i(TAG, "event=auto_torch_unavailable reason=no_continuous_luminance_capability")
            return
        }
        lifecycleScope.launch {
            val result = deviceRuntime.applyLocalCameraControl(CameraControlIds.TORCH, desired)
            if (result.succeeded) {
                renderLiveCameraControls()
            } else {
                Toast.makeText(this@MainActivity, R.string.camera_flash_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun publishCameraState(
        state: DeviceCameraState,
        availableCameraCount: Int = deviceRuntime.device.state.value.camera.availableCameraCount,
    ) {
        deviceRuntime.publishCameraState(
            CameraState(
                state = state,
                selectedLens = when (lensSwitchController.state.selectedLens) {
                    CameraLensFacing.REAR -> DeviceCameraLens.BACK
                    CameraLensFacing.FRONT -> DeviceCameraLens.FRONT
                },
                availableCameraCount = availableCameraCount,
            ),
        )
    }

    private fun SettingsRecordingCamera.toLensFacing(): CameraLensFacing = when (this) {
        SettingsRecordingCamera.REAR -> CameraLensFacing.REAR
        SettingsRecordingCamera.FRONT -> CameraLensFacing.FRONT
    }

    private fun CameraLensFacing.toSettingsCamera(): SettingsRecordingCamera = when (this) {
        CameraLensFacing.REAR -> SettingsRecordingCamera.REAR
        CameraLensFacing.FRONT -> SettingsRecordingCamera.FRONT
    }

    private fun canOpenSettings(state: RecordingState): Boolean =
        RecordingEngineSettingsPolicy.canEdit(state)

    private fun RecordingState.isRecordingSessionActive(): Boolean = when (this) {
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> true
        else -> false
    }

    private fun com.ashraffarag.sentricam.recording.settings.domain.RecordingVideoQuality.toProfileId(): RecordingProfileId =
        when (this) {
            com.ashraffarag.sentricam.recording.settings.domain.RecordingVideoQuality.HD ->
                RecordingProfileId.STANDARD
            com.ashraffarag.sentricam.recording.settings.domain.RecordingVideoQuality.FULL_HD ->
                RecordingProfileId.HIGH
        }

    private fun SettingsRecordingCamera.toRecordingLens(): RecordingLens = when (this) {
        SettingsRecordingCamera.REAR -> RecordingLens.BACK
        SettingsRecordingCamera.FRONT -> RecordingLens.FRONT
    }

    private fun microphonePermissionPermanentlyDenied(): Boolean =
        permissionWasRequested(KEY_MICROPHONE_PERMISSION_REQUESTED) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO,
            )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionWasRequested(key: String): Boolean =
        permissionPreferences.getBoolean(key, false)

    private companion object {
        const val TAG = "MainActivity"
        const val PERMISSION_PREFERENCES = "permissions"
        const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_requested"
        const val KEY_MICROPHONE_PERMISSION_REQUESTED = "microphone_requested"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_requested"
        const val MONITORING_RESTART_TIMEOUT_MILLIS = 15_000L
        const val LIVE_ZOOM_DEBOUNCE_MILLIS = 80L
    }
}
