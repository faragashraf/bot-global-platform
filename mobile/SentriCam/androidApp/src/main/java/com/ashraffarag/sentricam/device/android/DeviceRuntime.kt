package com.ashraffarag.sentricam.device.android

import android.content.Context
import android.util.Log
import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.capability.domain.DevelopmentEntitlementService
import com.ashraffarag.sentricam.capability.domain.CapabilityGuard
import com.ashraffarag.sentricam.communication.signalr.MicrosoftSignalRClient
import com.ashraffarag.sentricam.communication.signalr.SecureSignalRCredentialProvider
import com.ashraffarag.sentricam.communication.signalr.SignalRClientFactory
import com.ashraffarag.sentricam.communication.signalr.SignalRClock
import com.ashraffarag.sentricam.communication.signalr.SignalRConfigurationProvider
import com.ashraffarag.sentricam.communication.signalr.SignalRConnectionManager
import com.ashraffarag.sentricam.communication.signalr.android.AndroidSignalRLogger
import com.ashraffarag.sentricam.communication.signalr.SignalRState
import com.ashraffarag.sentricam.communication.signalr.android.AndroidSignalRNetworkMonitor
import com.ashraffarag.sentricam.communication.signalr.android.SharedPreferencesSnapshotVersionProvider
import com.ashraffarag.sentricam.device.command.DefaultDeviceCommandHandler
import com.ashraffarag.sentricam.device.command.DeviceCommandHandler
import com.ashraffarag.sentricam.device.command.MotionDetectionCommandPort
import com.ashraffarag.sentricam.device.command.RecordingCommandPort
import com.ashraffarag.sentricam.device.command.RecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.command.RemoteMonitoringCommandExecutor
import com.ashraffarag.sentricam.device.capability.CameraOperatingModeRuntimePolicyResolver
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.domain.CameraState
import com.ashraffarag.sentricam.device.domain.DefaultDevice
import com.ashraffarag.sentricam.device.domain.DefaultDeviceIdentityRepository
import com.ashraffarag.sentricam.device.domain.DeviceCameraLens
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.DeviceClock
import com.ashraffarag.sentricam.device.domain.DeviceState
import com.ashraffarag.sentricam.device.domain.DeviceStatusMapper
import com.ashraffarag.sentricam.device.health.DefaultDeviceHealthProvider
import com.ashraffarag.sentricam.device.health.MutableActivityHistoryProvider
import com.ashraffarag.sentricam.device.recovery.DeviceRecoveryCoordinator
import com.ashraffarag.sentricam.device.recovery.DeviceRecoveryReasons
import com.ashraffarag.sentricam.device.recovery.OperationalHealthState
import com.ashraffarag.sentricam.device.recovery.OperationalLifecycleState
import com.ashraffarag.sentricam.device.recovery.OperationalSubsystem
import com.ashraffarag.sentricam.device.recovery.RecoveryClock
import com.ashraffarag.sentricam.device.recovery.SharedPreferencesRecoveryStateStore
import com.ashraffarag.sentricam.device.time.DeviceTimeValidationController
import com.ashraffarag.sentricam.device.time.AndroidHubTimeVerificationLogger
import com.ashraffarag.sentricam.device.time.HubTimeVerificationCoordinator
import com.ashraffarag.sentricam.device.time.HubTimeVerificationCredentials
import com.ashraffarag.sentricam.device.time.HubTimeVerificationHttpClient
import com.ashraffarag.sentricam.device.registration.AndroidRegistrationRequestMapper
import com.ashraffarag.sentricam.device.registration.DeviceRegistrationCoordinator
import com.ashraffarag.sentricam.device.registration.DeviceRegistrationRepository
import com.ashraffarag.sentricam.device.registration.RegistrationClock
import com.ashraffarag.sentricam.device.registration.RegistrationConnectivity
import com.ashraffarag.sentricam.device.registration.RegistrationState
import com.ashraffarag.sentricam.device.registration.SentriCamApiClient
import com.ashraffarag.sentricam.device.registration.android.AndroidRegistrationLogger
import com.ashraffarag.sentricam.device.registration.android.AndroidServerConfiguration
import com.ashraffarag.sentricam.device.registration.android.EncryptedDeviceCredentialStore
import com.ashraffarag.sentricam.monitoring.android.AndroidMonitoringServiceController
import com.ashraffarag.sentricam.monitoring.android.SharedPreferencesMonitoringSettingsRepository
import com.ashraffarag.sentricam.monitoring.domain.CameraOwnershipCoordinator
import com.ashraffarag.sentricam.monitoring.domain.MonitoringServiceController
import com.ashraffarag.sentricam.onboarding.FirstRunCoordinator
import com.ashraffarag.sentricam.onboarding.SharedPreferencesFirstRunStateStore
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.android.SharedPreferencesMotionSettingsRepository
import com.ashraffarag.sentricam.motion.android.MotionSettingsCommandCameraControl
import com.ashraffarag.sentricam.motion.capability.DefaultMotionSettingsCapabilityReporter
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.upload.android.AndroidRecordingUploadCandidateScanner
import com.ashraffarag.sentricam.recording.upload.android.SharedPreferencesRecordingUploadQueue
import com.ashraffarag.sentricam.recording.upload.android.WorkManagerRecordingUploadScheduler
import com.ashraffarag.sentricam.recording.upload.capability.RecordingUploadCoordinator
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.ashraffarag.sentricam.live.android.AndroidPreviewController
import com.ashraffarag.sentricam.live.android.LiveViewDiagnostics
import com.ashraffarag.sentricam.live.capability.AttachableCameraStreamController
import com.ashraffarag.sentricam.live.capability.CameraStreamController
import com.ashraffarag.sentricam.live.domain.LiveSessionState
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraCapabilityEngine
import com.ashraffarag.sentricam.cameracontrol.android.AndroidCameraControlLogger
import com.ashraffarag.sentricam.cameracontrol.android.SharedPreferencesCameraControlSettingsRepository
import com.ashraffarag.sentricam.cameracontrol.android.RecordingCommandCameraControl
import com.ashraffarag.sentricam.cameracontrol.capability.AttachableCameraControlHardware
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlHardware
import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlService
import com.ashraffarag.sentricam.cameracontrol.capability.cameraControlDeviceId
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.recording.engine.android.SharedPreferencesRecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.settings.domain.RecordingVideoQuality
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class DeviceRuntime(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = DeviceClock(System::currentTimeMillis)
    private val identityRepository = DefaultDeviceIdentityRepository(
        store = SharedPreferencesDeviceIdentityStore(appContext),
        metadata = AndroidDeviceMetadata.current(),
        clock = clock,
    )
    private val battery = AndroidBatteryStateProvider(appContext)
    private val storage = AndroidStorageStateProvider(appContext)
    private val memory = AndroidMemorySummaryProvider(appContext)
    private val activity = MutableActivityHistoryProvider()
    private val connectivity = AndroidConnectivityProvider(appContext)
    private val health = DefaultDeviceHealthProvider(battery, storage, memory, activity, scope)
    private val recovery = DeviceRecoveryCoordinator(
        RecoveryClock(clock::nowMillis),
        SharedPreferencesRecoveryStateStore(appContext),
    )
    private var recordingStateJob: Job? = null
    private var motionStateJob: Job? = null
    private var liveSessionStateJob: Job? = null
    private var hubTimeVerificationJob: Job? = null
    private var remoteConnectivityJob: Job? = null
    private var registrationRefreshJob: Job? = null
    private var recordingUploadsBootstrapped = false
    private var uploadQueueObservation: AutoCloseable? = null
    private val cameraControlPublishLock = Any()
    private var lastCameraControlPublishSignature: String? = null
    private val recordingCommands = AttachableRecordingCommandPort()
    private val motionCommands = AttachableMotionDetectionCommandPort()
    private val recordingSettingsCommands = AttachableRecordingSettingsCommandPort()
    private val recordingUploadQueue = SharedPreferencesRecordingUploadQueue(appContext)
    private val recordingUploadScanner = AndroidRecordingUploadCandidateScanner(appContext)
    private val recordingUploads = RecordingUploadCoordinator(
        recordingUploadQueue,
        WorkManagerRecordingUploadScheduler(appContext),
    )

    val entitlements = DevelopmentEntitlementService()
    val monitoringSettings = SharedPreferencesMonitoringSettingsRepository(appContext)
    val motionSettings = SharedPreferencesMotionSettingsRepository(appContext, BuildConfig.DEBUG)
    val firstRun = FirstRunCoordinator(
        SharedPreferencesFirstRunStateStore(appContext),
        monitoringSettings,
    )
    val cameraOwnership = CameraOwnershipCoordinator()
    val liveCameraStream = AttachableCameraStreamController()
    val livePreview = AndroidPreviewController()
    private val mutableLiveSessionState = MutableStateFlow<LiveSessionState>(LiveSessionState.Idle)
    val liveSessionState = mutableLiveSessionState.asStateFlow()
    private val cameraControlSettings = SharedPreferencesCameraControlSettingsRepository(appContext)
    val cameraControlHardware = AttachableCameraControlHardware()
    private val mutableCameraReconfigureRequests = MutableSharedFlow<CompletableDeferred<Boolean>>(extraBufferCapacity = 1)
    val cameraReconfigureRequests = mutableCameraReconfigureRequests.asSharedFlow()
    val monitoring: MonitoringServiceController = AndroidMonitoringServiceController(
        appContext,
        clock,
        monitoringSettings,
    )
    val device = DefaultDevice(
        initialState = DeviceState(
            identity = identityRepository.start(),
            capabilities = AndroidDeviceCapabilitiesResolver(appContext, entitlements).resolve(),
            motionConfiguration = motionSettings.load(),
            operationalHealth = recovery.state.value,
        ),
        mapper = DeviceStatusMapper(clock),
    )
    val commandHandler: DeviceCommandHandler = DefaultDeviceCommandHandler(
        device = device,
        monitoring = monitoring,
        recording = recordingCommands,
        motion = motionCommands,
        recordingSettings = recordingSettingsCommands,
        capabilityGuard = CapabilityGuard(entitlements) { capability ->
            device.state.value.capabilities.access(capability)
        },
    )
    val serverConfiguration = AndroidServerConfiguration(appContext)
    val remoteConnectivity = AndroidDeviceConnectivityController(appContext)
    private val credentialStore = EncryptedDeviceCredentialStore(appContext)
    val registration = DeviceRegistrationCoordinator(
        repository = DeviceRegistrationRepository(
            api = SentriCamApiClient(),
            credentialStore = credentialStore,
            serverConfiguration = serverConfiguration,
            connectivity = RegistrationConnectivity { connectivity.state.value.networkAvailable },
            clock = RegistrationClock(clock::nowMillis),
            mapper = AndroidRegistrationRequestMapper(),
            logger = AndroidRegistrationLogger(),
        ),
        identity = { device.state.value.identity },
        capabilities = { device.state.value.capabilities },
        clock = RegistrationClock(clock::nowMillis),
        scope = scope,
    )
    private val mutableOperatingMode = MutableStateFlow(registration.operatingMode())
    val operatingMode = mutableOperatingMode.asStateFlow()
    private val signalRClock = SignalRClock(clock::nowMillis)
    val timeValidation = DeviceTimeValidationController(nowMillis = clock::nowMillis)
    private val hubTimeVerification = HubTimeVerificationCoordinator(
        api = HubTimeVerificationHttpClient(
            client = SentriCamApiClient.defaultClient(),
            nowMillis = clock::nowMillis,
        ),
        credentials = {
            runCatching { credentialStore.load() }.getOrNull()?.let { credentials ->
                HubTimeVerificationCredentials(
                    hubBaseUrl = credentials.details.serverBaseUrl,
                    accessToken = credentials.accessToken,
                )
            }
        },
        validation = timeValidation,
        logger = AndroidHubTimeVerificationLogger(),
    )
    val remoteMonitoringCommands = RemoteMonitoringCommandExecutor(commandHandler, signalRClock)
    val signalR = SignalRConnectionManager(
        clientFactory = SignalRClientFactory(::MicrosoftSignalRClient),
        configurationProvider = SignalRConfigurationProvider(serverConfiguration),
        credentialProvider = SecureSignalRCredentialProvider(credentialStore, signalRClock),
        networkMonitor = AndroidSignalRNetworkMonitor(connectivity.state, scope),
        snapshots = device.snapshot,
        snapshotVersions = SharedPreferencesSnapshotVersionProvider(appContext, signalRClock),
        clock = signalRClock,
        scope = scope,
        logger = AndroidSignalRLogger(
            deviceId = { registration.state.value.details?.serverDeviceId },
            networkAvailable = { connectivity.state.value.networkAvailable },
        ),
        pairingRevoked = ::handlePairingRevoked,
    )
    val cameraControl = CameraControlService(
        // Commands are addressed to the Hub-issued device id carried by the authenticated
        // SignalR connection. The installation id can legitimately differ after an app-data
        // restore or re-pair, so it must not be used to reject an authenticated command.
        localDeviceId = {
            cameraControlDeviceId(
                registration.state.value.details?.serverDeviceId,
                { credentialStore.load()?.details?.serverDeviceId },
                device.state.value.identity.deviceId,
            )
        },
        settings = cameraControlSettings,
        hardware = cameraControlHardware,
        recording = RecordingCommandCameraControl(recordingCommands),
        motion = MotionSettingsCommandCameraControl(
            repository = motionSettings,
            commands = commandHandler,
            snapshot = { device.snapshot.value },
            isDebug = BuildConfig.DEBUG,
        ),
        capabilities = AndroidCameraCapabilityEngine(
            appContext,
            cameraControlSettings,
            { device.snapshot.value },
            { mutableLiveSessionState.value is LiveSessionState.Connected },
            recordingUploadQueue::latest,
            DefaultMotionSettingsCapabilityReporter(motionSettings, BuildConfig.DEBUG),
        ),
        signaling = signalR,
        onSettingsChanged = ::synchronizeCameraSettings,
        scope = scope,
        logger = AndroidCameraControlLogger(),
    )

    init {
        uploadQueueObservation = recordingUploadQueue.observe {
            publishUploadHealth()
            publishCameraControlStateIfChanged()
        }
        livePreview.setPresentation(cameraControlSettings.load().preview)
        activity.markActivity(clock.nowMillis())
        scope.launch {
            recovery.state.collectLatest { value -> device.update { it.copy(operationalHealth = value) } }
        }
        scope.launch {
            health.state.collectLatest { value ->
                device.update { it.copy(health = value) }
                val batteryLow = value.battery.levelPercent?.let { it < 15 } == true
                recovery.update(
                    OperationalSubsystem.BATTERY,
                    OperationalLifecycleState.RUNNING,
                    if (batteryLow) OperationalHealthState.DEGRADED else OperationalHealthState.HEALTHY,
                    if (batteryLow) "battery_low" else null,
                )
                recovery.update(
                    OperationalSubsystem.STORAGE,
                    if (value.storage.isLow) OperationalLifecycleState.ACTION_REQUIRED else OperationalLifecycleState.RUNNING,
                    if (value.storage.isLow) OperationalHealthState.ACTION_REQUIRED else OperationalHealthState.HEALTHY,
                    if (value.storage.isLow) "storage_low" else null,
                )
            }
        }
        scope.launch {
            connectivity.state.collectLatest { value -> device.update { it.copy(connectivity = value) } }
        }
        scope.launch {
            monitoring.state.collectLatest { value ->
                device.update { it.copy(monitoring = value) }
                if (value.failureCode == DeviceRecoveryReasons.FOREGROUND_CAMERA_ACTION_REQUIRED) {
                    recovery.update(
                        OperationalSubsystem.CAMERA,
                        OperationalLifecycleState.ACTION_REQUIRED,
                        OperationalHealthState.ACTION_REQUIRED,
                        DeviceRecoveryReasons.FOREGROUND_CAMERA_ACTION_REQUIRED,
                    )
                    recovery.update(
                        OperationalSubsystem.MOTION,
                        OperationalLifecycleState.ACTION_REQUIRED,
                        OperationalHealthState.ACTION_REQUIRED,
                        DeviceRecoveryReasons.FOREGROUND_CAMERA_ACTION_REQUIRED,
                    )
                }
            }
        }
        remoteConnectivityJob = scope.launch {
            var connectivityStarted = false
            registration.state.collectLatest { state ->
                val mode = registration.operatingMode()
                mutableOperatingMode.value = mode
                val policy = CameraOperatingModeRuntimePolicyResolver.resolve(mode, state)
                when {
                    policy.startHubConnectivity -> {
                        registrationRefreshJob?.cancel()
                        state.details?.accessTokenExpiresAtMillis?.let { expiresAt ->
                            registrationRefreshJob = scope.launch {
                                delay((expiresAt - clock.nowMillis()).coerceAtLeast(0L) + 1_000L)
                                registration.refreshExpiredCredentials()
                            }
                        }
                        if (!connectivityStarted) connectivityStarted = remoteConnectivity.start()
                        val shouldBootstrap = !recordingUploadsBootstrapped
                        recordingUploadsBootstrapped = true
                        scope.launch(Dispatchers.IO) {
                            if (shouldBootstrap) {
                                recordingUploads.enqueue(recordingUploadScanner.scan(), schedule = false)
                                recordingUploads.restoreAndSchedule()
                            }
                            recordingUploads.retryFailedAfterRegistration()
                        }
                    }
                    policy.stopHubConnectivity -> {
                        registrationRefreshJob?.cancel()
                        registrationRefreshJob = null
                        recordingUploadsBootstrapped = false
                        if (connectivityStarted) {
                            remoteConnectivity.stop()
                            connectivityStarted = false
                        }
                        if (mode is CameraOperatingMode.Standalone) {
                            publishStandaloneRealtimeHealth()
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            var lastVerifiedConnectionId: String? = null
            signalR.state.collect { state ->
                publishRealtimeHealth(state, operatingMode.value)
                when (state) {
                    is SignalRState.Connected -> if (state.connectionId != lastVerifiedConnectionId) {
                        lastVerifiedConnectionId = state.connectionId
                        replaceHubTimeVerification(verify = true)
                    }
                    is SignalRState.AuthenticationFailed -> {
                        lastVerifiedConnectionId = null
                        replaceHubTimeVerification(verify = false)
                        // Authentication health does not redefine the persisted operating mode.
                        // Reuse the existing credential refresh path while local camera and
                        // monitoring capabilities continue operating.
                        if (operatingMode.value is CameraOperatingMode.HubManaged) {
                            registration.refreshRejectedCredentials()
                        }
                    }
                    is SignalRState.Disconnected,
                    is SignalRState.Reconnecting,
                    is SignalRState.ServerUnavailable,
                    is SignalRState.Error,
                    SignalRState.Stopped,
                    -> {
                        lastVerifiedConnectionId = null
                        replaceHubTimeVerification(verify = false)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            signalR.metrics.collectLatest { metrics ->
                recovery.update(
                    OperationalSubsystem.COMMAND_QUEUE,
                    if (metrics.commandsInFlight > 0) OperationalLifecycleState.RUNNING else OperationalLifecycleState.IDLE,
                    OperationalHealthState.HEALTHY,
                    reconnectCount = metrics.reconnectAttempts,
                )
            }
        }
        publishUploadHealth()
        if (operatingMode.value is CameraOperatingMode.HubManaged) {
            registration.maybeRegisterAutomatically()
        }
    }

    fun renameDevice(name: String) {
        val identity = identityRepository.rename(name)
        device.update { it.copy(identity = identity) }
    }

    fun publishRecordingState(state: RecordingState) {
        val previous = device.state.value.recording
        device.update { it.copy(recording = state) }
        publishRecordingHealth(state)
        if (state is RecordingState.Completed && previous !is RecordingState.Completed) {
            activity.markRecording(clock.nowMillis())
            state.result.segments.forEach(::recordingSegmentFinalized)
        }
        storage.refresh()
        memory.refresh()
        publishCameraControlStateIfChanged()
    }

    fun recordingSegmentFinalized(segment: RecordingSegmentMetadata) {
        if (!segment.finalized || segment.fileSizeBytes <= 0L) return
        val enqueued = recordingUploads.enqueueCompleted(
            segment,
            schedule = registration.state.value is RegistrationState.Registered,
        )
        if (enqueued) {
            Log.i(
                RECORDING_UPLOAD_TAG,
                "event=recording_finalized recording=${segment.segmentId} session=${segment.sessionId} bytes=${segment.fileSizeBytes}",
            )
            Log.i(RECORDING_UPLOAD_TAG, "event=upload_queued recording=${segment.segmentId}")
        }
        publishCameraControlStateIfChanged()
    }

    fun attachRecordingState(state: StateFlow<RecordingState>) {
        recordingStateJob?.cancel()
        recordingStateJob = scope.launch {
            state.collectLatest(::publishRecordingState)
        }
    }

    fun detachRecordingState() {
        recordingStateJob?.cancel()
        recordingStateJob = null
        publishRecordingState(RecordingState.Idle)
    }

    fun publishCameraState(state: CameraState) {
        device.update { it.copy(camera = state) }
        publishCameraHealth(state)
    }

    fun attachMotionState(
        state: StateFlow<MotionDetectionState>,
        configuration: MotionDetectionConfig,
    ) {
        publishMotionConfiguration(configuration)
        motionStateJob?.cancel()
        motionStateJob = scope.launch { state.collectLatest(::publishMotionState) }
    }

    fun attachLiveCameraStream(controller: CameraStreamController) = liveCameraStream.attach(controller)

    fun detachLiveCameraStream() = liveCameraStream.detach()

    fun attachLiveSessionState(state: StateFlow<LiveSessionState>) {
        liveSessionStateJob?.cancel()
        liveSessionStateJob = scope.launch {
            state.collectLatest {
                mutableLiveSessionState.value = it
                publishLiveHealth(it)
                LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                    "event=runtime_state state=${it.javaClass.simpleName}"
                }
                publishCameraControlStateIfChanged()
            }
        }
    }

    fun detachLiveSessionState() {
        liveSessionStateJob?.cancel()
        liveSessionStateJob = null
        mutableLiveSessionState.value = LiveSessionState.Idle
        publishLiveHealth(LiveSessionState.Idle)
    }

    fun attachCameraControlHardware(hardware: CameraControlHardware) {
        cameraControlHardware.attach(hardware)
        scope.launch { cameraControl.restoreAttachedHardware() }
    }

    fun detachCameraControlHardware(hardware: CameraControlHardware? = null) =
        cameraControlHardware.detach(hardware)

    suspend fun requestCameraReconfigure(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        mutableCameraReconfigureRequests.emit(completion)
        return withTimeoutOrNull(15_000) { completion.await() } ?: false
    }

    fun preserveLiveConsumerForCameraReplacement() = liveCameraStream.preserveConsumerForReplacement()

    fun cameraControlSettings(): CameraControlSettings = cameraControlSettings.load()

    fun cameraControlState(): CameraControlDeviceReport =
        cameraControl.state(authenticatedCameraControlDeviceId())

    suspend fun applyLocalCameraControl(
        control: String,
        desiredSettings: CameraControlSettings,
    ): CameraHardwareResult = cameraControl.applyLocal(control, desiredSettings)

    fun detachMotionState() {
        motionStateJob?.cancel()
        motionStateJob = null
        publishMotionState(MotionDetectionState.Disabled)
    }

    fun attachCommandPorts(
        recording: RecordingCommandPort,
        motion: MotionDetectionCommandPort,
        recordingSettings: RecordingSettingsCommandPort,
    ) {
        recordingCommands.attach(recording)
        motionCommands.attach(motion)
        recordingSettingsCommands.attach(recordingSettings)
    }

    fun detachCommandPorts() {
        recordingCommands.detach()
        motionCommands.detach()
        recordingSettingsCommands.detach()
    }

    fun publishMotionConfiguration(configuration: MotionDetectionConfig) {
        device.update { it.copy(motionConfiguration = configuration) }
        publishCameraControlStateIfChanged()
    }

    fun publishMotionState(state: MotionDetectionState) {
        val previousEvent = device.state.value.motion.event()
        device.update { it.copy(motion = state) }
        publishMotionHealth(state)
        val event = state.event()
        if (event != null && event.lastMotionAtMillis != previousEvent?.lastMotionAtMillis) {
            activity.markMotion(event.lastMotionAtMillis)
        }
    }

    fun markActivity() {
        activity.markActivity(clock.nowMillis())
        storage.refresh()
        memory.refresh()
    }

    fun retryTimeValidation() {
        if (operatingMode.value is CameraOperatingMode.HubManaged) {
            scope.launch { hubTimeVerification.verify() }
        }
    }

    private fun replaceHubTimeVerification(verify: Boolean) {
        val previous = hubTimeVerificationJob
        hubTimeVerificationJob = scope.launch {
            previous?.cancelAndJoin()
            if (verify) hubTimeVerification.verify()
        }
    }

    fun currentLens(): DeviceCameraLens = device.state.value.camera.selectedLens

    fun capabilitySummary(): Pair<Int, Int> {
        val all = device.state.value.capabilities.all()
        return all.count { it.value == CapabilityAccess.Available } to all.count {
            it.value == CapabilityAccess.ComingSoon
        }
    }

    fun isCapabilityAvailable(capability: AppCapability): Boolean =
        device.state.value.capabilities.isAvailable(capability)

    override fun close() {
        uploadQueueObservation?.close()
        uploadQueueObservation = null
        cameraControl.close()
        liveCameraStream.detach()
        signalR.close()
        remoteConnectivityJob?.cancel()
        registrationRefreshJob?.cancel()
        recordingStateJob?.cancel()
        motionStateJob?.cancel()
        detachCommandPorts()
        battery.close()
        storage.close()
        connectivity.close()
        scope.cancel()
    }

    private fun publishCameraControlStateIfChanged() {
        val report = cameraControl.state(authenticatedCameraControlDeviceId())
        val upload = report.telemetry.latestRecordingUpload
        val signature = listOf(
            report.telemetry.recordingState,
            report.telemetry.recordingOrigin,
            report.telemetry.streaming,
            upload?.clientRecordingId,
            upload?.state,
            upload?.progressPercent?.div(10),
            upload?.lastErrorCode,
            upload?.serverRecordingId,
            motionSettings.loadVersioned().configuration,
            motionSettings.loadVersioned().version,
        ).joinToString("|")
        val changed = synchronized(cameraControlPublishLock) {
            if (lastCameraControlPublishSignature == signature) {
                false
            } else {
                lastCameraControlPublishSignature = signature
                true
            }
        }
        if (changed && operatingMode.value is CameraOperatingMode.HubManaged) {
            scope.launch { cameraControl.publishState() }
        }
    }

    private fun publishRealtimeHealth(state: SignalRState, mode: CameraOperatingMode) {
        if (mode is CameraOperatingMode.Standalone) {
            publishStandaloneRealtimeHealth()
            return
        }
        when (state) {
            SignalRState.Stopped -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.OFFLINE,
                OperationalHealthState.OFFLINE,
                "service_stopped",
            )
            is SignalRState.Connecting -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.STARTING,
                OperationalHealthState.RECOVERING,
                "connecting",
                state.reconnectAttempts,
            )
            is SignalRState.Connected -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.RUNNING,
                OperationalHealthState.HEALTHY,
                reconnectCount = state.reconnectAttempts,
            )
            is SignalRState.Disconnected -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.RECOVERING,
                OperationalHealthState.RECOVERING,
                state.reasonCode ?: "connection_closed",
                state.reconnectAttempts,
            )
            is SignalRState.Reconnecting -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.RECOVERING,
                OperationalHealthState.RECOVERING,
                state.reasonCode,
                state.reconnectAttempts,
            )
            is SignalRState.ServerUnavailable -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.OFFLINE,
                OperationalHealthState.OFFLINE,
                state.reasonCode,
                state.reconnectAttempts,
            )
            is SignalRState.AuthenticationFailed -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.ACTION_REQUIRED,
                OperationalHealthState.ACTION_REQUIRED,
                state.reasonCode,
                state.reconnectAttempts,
            )
            is SignalRState.Error -> recovery.update(
                OperationalSubsystem.REALTIME,
                OperationalLifecycleState.FAILED,
                OperationalHealthState.DEGRADED,
                state.reasonCode,
                state.reconnectAttempts,
            )
        }
    }

    private fun publishStandaloneRealtimeHealth() {
        recovery.update(
            OperationalSubsystem.REALTIME,
            OperationalLifecycleState.IDLE,
            OperationalHealthState.HEALTHY,
        )
    }

    private fun publishLiveHealth(state: LiveSessionState) = when (state) {
        LiveSessionState.Idle -> recovery.update(
            OperationalSubsystem.LIVE,
            OperationalLifecycleState.IDLE,
            OperationalHealthState.HEALTHY,
        )
        is LiveSessionState.Connecting,
        is LiveSessionState.Negotiating,
        is LiveSessionState.Buffering,
        -> recovery.update(
            OperationalSubsystem.LIVE,
            OperationalLifecycleState.RECOVERING,
            OperationalHealthState.RECOVERING,
            "live_negotiating",
        )
        is LiveSessionState.Connected -> recovery.update(
            OperationalSubsystem.LIVE,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
        )
        is LiveSessionState.Failed -> recovery.update(
            OperationalSubsystem.LIVE,
            OperationalLifecycleState.FAILED,
            OperationalHealthState.DEGRADED,
            state.code,
        )
    }

    private fun publishRecordingHealth(state: RecordingState) = when (state) {
        RecordingState.Idle,
        is RecordingState.Completed,
        -> recovery.update(
            OperationalSubsystem.RECORDING,
            OperationalLifecycleState.IDLE,
            OperationalHealthState.HEALTHY,
        )
        is RecordingState.Failed -> recovery.update(
            OperationalSubsystem.RECORDING,
            if (state.failure.retryAllowed) OperationalLifecycleState.RECOVERING else OperationalLifecycleState.ACTION_REQUIRED,
            if (state.failure.retryAllowed) OperationalHealthState.RECOVERING else OperationalHealthState.ACTION_REQUIRED,
            state.failure.code.stableCode,
        )
        else -> recovery.update(
            OperationalSubsystem.RECORDING,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
        )
    }

    private fun publishMotionHealth(state: MotionDetectionState) = when (state) {
        MotionDetectionState.Disabled -> recovery.update(
            OperationalSubsystem.MOTION,
            OperationalLifecycleState.IDLE,
            OperationalHealthState.HEALTHY,
        )
        is MotionDetectionState.Initializing -> recovery.update(
            OperationalSubsystem.MOTION,
            OperationalLifecycleState.STARTING,
            OperationalHealthState.RECOVERING,
            "motion_initializing",
        )
        is MotionDetectionState.Error -> recovery.update(
            OperationalSubsystem.MOTION,
            if (state.failure.retryAllowed) OperationalLifecycleState.RECOVERING else OperationalLifecycleState.FAILED,
            if (state.failure.retryAllowed) OperationalHealthState.RECOVERING else OperationalHealthState.DEGRADED,
            state.failure.code.stableCode,
        )
        else -> recovery.update(
            OperationalSubsystem.MOTION,
            OperationalLifecycleState.RUNNING,
            OperationalHealthState.HEALTHY,
        )
    }

    private fun publishCameraHealth(state: CameraState) {
        val monitoringExpected = monitoringSettings.load().enabled
        when (state.state) {
            DeviceCameraState.READY -> recovery.update(
                OperationalSubsystem.CAMERA,
                OperationalLifecycleState.RUNNING,
                OperationalHealthState.HEALTHY,
            )
            DeviceCameraState.STARTING,
            DeviceCameraState.SWITCHING,
            -> recovery.update(
                OperationalSubsystem.CAMERA,
                OperationalLifecycleState.RECOVERING,
                OperationalHealthState.RECOVERING,
                "camera_reopening",
            )
            DeviceCameraState.ERROR -> recovery.update(
                OperationalSubsystem.CAMERA,
                OperationalLifecycleState.DEGRADED,
                OperationalHealthState.DEGRADED,
                "camera_failure",
            )
            DeviceCameraState.UNAVAILABLE -> recovery.update(
                OperationalSubsystem.CAMERA,
                if (monitoringExpected) OperationalLifecycleState.RECOVERING else OperationalLifecycleState.IDLE,
                if (monitoringExpected) OperationalHealthState.RECOVERING else OperationalHealthState.HEALTHY,
                if (monitoringExpected) "camera_unavailable" else null,
            )
        }
    }

    private fun publishUploadHealth() {
        val item = recordingUploadQueue.latest()
        when (item?.state) {
            null,
            RecordingUploadState.UPLOADED,
            -> recovery.update(
                OperationalSubsystem.UPLOAD,
                OperationalLifecycleState.IDLE,
                OperationalHealthState.HEALTHY,
            )
            RecordingUploadState.QUEUED,
            RecordingUploadState.UPLOADING,
            RecordingUploadState.RETRYING,
            -> recovery.update(
                OperationalSubsystem.UPLOAD,
                OperationalLifecycleState.RECOVERING,
                OperationalHealthState.RECOVERING,
                item.lastErrorCode ?: "upload_pending",
                reconnectCount = item.attemptCount,
            )
            RecordingUploadState.FAILED -> recovery.update(
                OperationalSubsystem.UPLOAD,
                OperationalLifecycleState.DEGRADED,
                OperationalHealthState.DEGRADED,
                item.lastErrorCode ?: "upload_failed",
                reconnectCount = item.attemptCount,
            )
        }
    }

    private fun authenticatedCameraControlDeviceId(): String = cameraControlDeviceId(
        registration.state.value.details?.serverDeviceId,
        { credentialStore.load()?.details?.serverDeviceId },
        device.state.value.identity.deviceId,
    )

    private fun handlePairingRevoked(revocation: com.ashraffarag.sentricam.communication.signalr.DevicePairingRevoked) {
        val currentDeviceId = registration.state.value.details?.serverDeviceId
        if (currentDeviceId.isNullOrBlank()
            || !currentDeviceId.equals(revocation.deviceId, ignoreCase = true)) {
            return
        }

        firstRun.pairingRemoved()
        registration.forgetRegistration()
        scope.launch { monitoring.stop() }
    }

    private fun synchronizeCameraSettings(settings: CameraControlSettings) {
        val recording = SharedPreferencesRecordingSettingsRepository(appContext)
        val current = recording.load()
        recording.save(
            current.copy(
                camera = if (settings.lens == CameraControlValues.FRONT) RecordingCamera.FRONT else RecordingCamera.REAR,
                videoQuality = if (settings.quality == CameraControlValues.HIGH) {
                    RecordingVideoQuality.FULL_HD
                } else {
                    RecordingVideoQuality.HD
                },
                overlay = settings.dateTimeOverlay,
            ),
        )
        val engine = SharedPreferencesRecordingEngineSettingsRepository(appContext, BuildConfig.DEBUG)
        val engineCurrent = engine.load()
        engine.save(
            engineCurrent.copy(
                profileId = when (settings.quality) {
                    CameraControlValues.LOW -> RecordingProfileId.LOW
                    CameraControlValues.HIGH -> RecordingProfileId.HIGH
                    else -> RecordingProfileId.STANDARD
                },
            ),
        )
    }

    private fun MotionDetectionState.event(): MotionEventSummary? = when (this) {
        is MotionDetectionState.MotionConfirmed -> event
        is MotionDetectionState.Holding -> event
        is MotionDetectionState.Cooldown -> event
        else -> null
    }

    private companion object {
        const val RECORDING_UPLOAD_TAG = "RecordingUpload"
    }
}
