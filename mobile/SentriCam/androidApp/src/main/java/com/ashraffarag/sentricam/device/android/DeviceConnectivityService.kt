package com.ashraffarag.sentricam.device.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.ashraffarag.sentricam.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ashraffarag.sentricam.MainActivity
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.SentriCamApplication
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.domain.DeviceCameraState
import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.recovery.DeviceRecoveryPolicies
import com.ashraffarag.sentricam.device.recovery.AndroidRecoveryPolicy
import com.ashraffarag.sentricam.device.recovery.DeviceRecoveryReasons
import com.ashraffarag.sentricam.device.registration.RegistrationState
import com.ashraffarag.sentricam.monitoring.android.MonitoringCameraSession
import com.ashraffarag.sentricam.monitoring.android.AndroidMonitoringNotificationText
import com.ashraffarag.sentricam.monitoring.domain.MonitoringNotificationContentMapper
import com.ashraffarag.sentricam.monitoring.domain.MonitoringRuntimeReporter
import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.recording.settings.android.SharedPreferencesRecordingSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.RecoveryAction
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ashraffarag.sentricam.live.android.AndroidLiveCameraCapabilityResolver
import com.ashraffarag.sentricam.live.android.AndroidWebRtcPublisher
import com.ashraffarag.sentricam.live.android.AndroidLiveSessionDiagnostics
import com.ashraffarag.sentricam.live.capability.LiveSessionManager
import com.ashraffarag.sentricam.live.android.LiveCameraHostSession

/**
 * The single foreground runtime for a registered SentriCam device.
 *
 * SignalR and heartbeat ownership live for the lifetime of this service. Camera monitoring is a
 * hosted capability that can start and stop without changing connectivity ownership.
 */
class DeviceConnectivityService : LifecycleService() {
    private val runtime by lazy { (application as SentriCamApplication).deviceRuntime }
    private val reporter get() = runtime.monitoring as MonitoringRuntimeReporter
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val monitoringNotificationText by lazy {
        AndroidMonitoringNotificationText(this)
    }
    private val sessionMutex = Mutex()
    private var session: MonitoringCameraSession? = null
    private var snapshotJob: Job? = null
    private var monitoringRecoveryAttempt = 0
    private var monitoringRecoveryJob: Job? = null
    private var runtimeFailureJob: Job? = null
    private var foregroundStarted = false
    private var connectivityRequested = false
    private var signalROwned = false
    private var lastNotificationFingerprint: String? = null
    private var liveSessions: LiveSessionManager? = null
    private var liveCameraHost: LiveCameraHostSession? = null
    private var cameraReconfigureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        RuntimeLifecycleDiagnostics.service("created")
        logServiceLifecycle("service_created")
        createNotificationChannel()
        val cameraHost = LiveCameraHostSession(
            applicationContext,
            this,
            runtime,
            runtime.livePreview,
            ::prepareLiveForeground,
            ::updateForegroundForCurrentOwnership,
        )
        liveCameraHost = cameraHost
        val capabilities = AndroidLiveCameraCapabilityResolver(
            applicationContext,
            cameraHost::readiness,
        )
        val liveDiagnostics = AndroidLiveSessionDiagnostics(
            deviceId = { runtime.registration.state.value.details?.serverDeviceId },
            connectionId = { runtime.signalR.metrics.value.connectionId },
        )
        val manager = LiveSessionManager(
            camera = runtime.liveCameraStream,
            previewController = runtime.livePreview,
            publisher = AndroidWebRtcPublisher(
                applicationContext,
                runtime::cameraControlSettings,
                liveDiagnostics,
            ),
            signaling = runtime.signalR,
            capabilityResolver = capabilities::resolve,
            scope = lifecycleScope,
            cameraLease = cameraHost,
            diagnostics = liveDiagnostics,
        )
        liveSessions = manager
        runtime.attachLiveSessionState(manager.state)
        runtimeFailureJob = lifecycleScope.launch {
            runtime.device.state
                .map { state ->
                    when {
                        state.camera.state == DeviceCameraState.ERROR -> "camera_failure"
                        state.motion is MotionDetectionState.Error && state.motion.failure.retryAllowed ->
                            state.motion.failure.code.stableCode
                        state.recording is RecordingState.Failed &&
                            state.recording.failure.retryAllowed &&
                            state.recording.failure.recoveryAction == RecoveryAction.RESTART_CAMERA ->
                            state.recording.failure.code.stableCode
                        else -> null
                    }
                }
                .distinctUntilChanged()
                .collect { reason ->
                    if (reason != null && runtime.monitoringSettings.load().enabled) {
                        scheduleMonitoringRecovery(reason)
                    }
                }
        }
        cameraReconfigureJob = lifecycleScope.launch {
            runtime.cameraReconfigureRequests.collect { completion ->
                completion.complete(runCatching { reconfigureActiveCamera() }.getOrDefault(false))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        RuntimeLifecycleDiagnostics.service("running")
        logServiceLifecycle("service_start_requested", intent?.action)
        when (intent?.action) {
            ACTION_START_CONNECTIVITY -> startConnectivity()
            ACTION_STOP_CONNECTIVITY -> stopConnectivity()
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_RESTART_MONITORING -> restartMonitoring()
            else -> restoreRuntime()
        }
        return if (connectivityRequested || runtime.monitoringSettings.load().enabled) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        logServiceLifecycle("service_destroyed")
        RuntimeLifecycleDiagnostics.service("destroyed")
        snapshotJob?.cancel()
        cameraReconfigureJob?.cancel()
        runtimeFailureJob?.cancel()
        monitoringRecoveryJob?.cancel()
        stopSignalRIfOwned()
        liveSessions?.close()
        liveSessions = null
        runtime.detachLiveSessionState()
        liveCameraHost?.close()
        liveCameraHost = null
        session?.let { active -> lifecycleScope.launch { active.stop() } }
        super.onDestroy()
    }

    private fun logServiceLifecycle(event: String, action: String? = null) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "event=$event deviceId=${runtime.registration.state.value.details?.serverDeviceId ?: "unknown"} " +
                "action=${action ?: "none"} appState=${RuntimeLifecycleDiagnostics.appState} " +
                "serviceState=${RuntimeLifecycleDiagnostics.serviceState} foreground=$foregroundStarted " +
                "connectivityRequested=$connectivityRequested signalROwned=$signalROwned",
        )
    }

    private fun restoreRuntime() {
        val registered = runtime.operatingMode.value is CameraOperatingMode.HubManaged &&
            runtime.registration.state.value is RegistrationState.Registered
        if (registered) startConnectivity()
        val settings = runtime.monitoringSettings.load()
        if (settings.enabled && settings.autoStart) {
            if (AndroidRecoveryPolicy.requiresForegroundCameraActionAfterBoot(Build.VERSION.SDK_INT)) {
                failMonitoringStart(DeviceRecoveryReasons.FOREGROUND_CAMERA_ACTION_REQUIRED)
            } else {
                startMonitoring()
            }
        }
        if (!registered && !(settings.enabled && settings.autoStart)) removeForegroundAndStop()
    }

    private fun startConnectivity() {
        if (runtime.operatingMode.value is CameraOperatingMode.Standalone) {
            connectivityRequested = false
            stopSignalRIfOwned()
            updateForegroundForCurrentOwnership()
            return
        }
        connectivityRequested = true
        if (!prepareConnectivityForeground(monitoringOwnsForegroundType())) {
            connectivityRequested = false
            return
        }
        if (!signalROwned) {
            runtime.signalR.start(
                runtime.remoteMonitoringCommands,
                checkNotNull(liveSessions),
                runtime.cameraControl,
            )
            signalROwned = true
        }
    }

    private fun stopConnectivity() {
        connectivityRequested = false
        stopSignalRIfOwned()
        lifecycleScope.launch {
            sessionMutex.withLock { updateForegroundForCurrentOwnership() }
        }
    }

    private fun stopSignalRIfOwned() {
        if (!signalROwned) return
        signalROwned = false
        runtime.signalR.stop()
    }

    private fun startMonitoring() {
        if (!runtime.monitoringSettings.load().enabled) {
            stopMonitoring()
            return
        }
        if (!prepareMonitoringForeground()) return
        lifecycleScope.launch {
            sessionMutex.withLock {
                if (!runtime.monitoringSettings.load().enabled) {
                    reporter.stopped()
                    updateForegroundForCurrentOwnership()
                    return@withLock
                }
                if (session != null) {
                    reporter.running()
                    return@withLock
                }
                try {
                    val newSession = MonitoringCameraSession(
                        applicationContext,
                        this@DeviceConnectivityService,
                        runtime,
                        lifecycleScope,
                    )
                    session = newSession
                    newSession.start()
                    monitoringRecoveryAttempt = 0
                    monitoringRecoveryJob?.cancel()
                    monitoringRecoveryJob = null
                    reporter.running()
                    observeDeviceSnapshot()
                } catch (failure: Throwable) {
                    Log.e(TAG, "Monitoring session failed to start", failure)
                    session?.stop()
                    session = null
                    val settings = runtime.monitoringSettings.load()
                    reporter.failed(failure.message ?: "monitoring_start_failed")
                    if (settings.restartOnFailure) {
                        scheduleMonitoringRecovery(failure.message ?: "monitoring_start_failed")
                    }
                    updateForegroundForCurrentOwnership()
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitoringRecoveryJob?.cancel()
        monitoringRecoveryJob = null
        monitoringRecoveryAttempt = 0
        lifecycleScope.launch {
            sessionMutex.withLock {
                snapshotJob?.cancel()
                snapshotJob = null
                session?.stop()
                session = null
                reporter.stopped()
                updateForegroundForCurrentOwnership()
            }
        }
    }

    private fun restartMonitoring() {
        if (!prepareMonitoringForeground(forceRefresh = true)) return
        lifecycleScope.launch {
            sessionMutex.withLock {
                reporter.restarting()
                snapshotJob?.cancel()
                snapshotJob = null
                session?.stop()
                session = null
            }
            startMonitoring()
        }
    }

    private suspend fun reconfigureActiveCamera(): Boolean {
        val liveHost = liveCameraHost
        if (liveHost?.active == true) {
            runtime.preserveLiveConsumerForCameraReplacement()
            liveHost.release()
            return liveHost.acquire() is com.ashraffarag.sentricam.live.capability.LiveCameraLeaseResult.Acquired
        }
        return sessionMutex.withLock {
            val current = session ?: return@withLock false
            reporter.restarting()
            runtime.preserveLiveConsumerForCameraReplacement()
            snapshotJob?.cancel()
            snapshotJob = null
            current.stop()
            session = null
            try {
                val replacement = MonitoringCameraSession(
                    applicationContext,
                    this@DeviceConnectivityService,
                    runtime,
                    lifecycleScope,
                )
                session = replacement
                replacement.start()
                reporter.running()
                observeDeviceSnapshot()
                true
            } catch (failure: Throwable) {
                Log.e(TAG, "Camera Control reconfiguration failed", failure)
                session?.stop()
                session = null
                reporter.failed(failure.message ?: "camera_reconfigure_failed")
                false
            }
        }
    }

    private fun prepareConnectivityForeground(monitoringActive: Boolean): Boolean = try {
        val notification = if (monitoringActive) {
            initialMonitoringNotification()
        } else {
            connectivityNotification()
        }
        publishForeground(notification, monitoringActive)
        true
    } catch (failure: RuntimeException) {
        Log.e(TAG, "Connectivity service could not enter the foreground", failure)
        false
    }

    private fun prepareMonitoringForeground(forceRefresh: Boolean = false): Boolean {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            failMonitoringStart("camera_permission_missing")
            return false
        }
        return try {
            if (forceRefresh || foregroundStarted) {
                publishForeground(initialMonitoringNotification(), monitoringActive = true)
            } else {
                ensureForeground(initialMonitoringNotification(), monitoringActive = true)
            }
            true
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Monitoring service could not acquire camera foreground access", failure)
            failMonitoringStart("foreground_camera_start_failed")
            false
        }
    }

    private fun prepareLiveForeground(): Boolean {
        if (!hasPermission(Manifest.permission.CAMERA)) return false
        return try {
            publishForeground(liveNotification(), monitoringActive = true)
            true
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Live View could not acquire camera foreground access", failure)
            false
        }
    }

    private fun failMonitoringStart(code: String) {
        lifecycleScope.launch {
            sessionMutex.withLock {
                snapshotJob?.cancel()
                snapshotJob = null
                session?.stop()
                session = null
                reporter.failed(code)
                updateForegroundForCurrentOwnership()
            }
        }
    }

    private fun scheduleMonitoringRecovery(reason: String) {
        if (monitoringRecoveryJob?.isActive == true) return
        val attempt = ++monitoringRecoveryAttempt
        val delayMillis = DeviceRecoveryPolicies.camera.delayForAttempt(attempt)
        Log.w(TAG, "Scheduling monitoring recovery attempt=$attempt delayMs=$delayMillis reason=$reason")
        monitoringRecoveryJob = lifecycleScope.launch {
            reporter.restarting()
            delay(delayMillis)
            if (runtime.monitoringSettings.load().enabled) startMonitoring()
        }
    }

    private fun observeDeviceSnapshot() {
        snapshotJob?.cancel()
        snapshotJob = lifecycleScope.launch {
            runtime.device.snapshot.collect { snapshot -> updateMonitoringNotification(snapshot) }
        }
    }

    private fun updateMonitoringNotification(snapshot: DeviceSnapshot) {
        if (session == null) return
        val options = runtime.monitoringSettings.load().notification
        val content = MonitoringNotificationContentMapper.map(snapshot, options, monitoringNotificationText)
        val fingerprint = "${content.statusLine}|${content.detailLine}|${content.storageWarning}"
        if (fingerprint == lastNotificationFingerprint) return
        val notification = monitoringNotificationBuilder()
            .setContentTitle(content.title)
            .setContentText(content.statusLine)
            .setSubText(content.detailLine)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(content.statusLine, content.detailLine).joinToString("\n"),
                ),
            )
            .setColorized(content.storageWarning)
            .build()
        try {
            publishForeground(notification, monitoringActive = true)
            lastNotificationFingerprint = fingerprint
        } catch (denied: SecurityException) {
            Log.w(TAG, "Notification permission denied; foreground service remains visible to the system", denied)
        }
    }

    private fun updateForegroundForCurrentOwnership() {
        lastNotificationFingerprint = null
        val liveActive = liveCameraHost?.active == true
        if (connectivityRequested) {
            publishForeground(
                if (liveActive) liveNotification() else connectivityNotification(),
                monitoringActive = liveActive || session != null,
            )
        } else if (session != null) {
            publishForeground(initialMonitoringNotification(), monitoringActive = true)
        } else {
            removeForegroundAndStop()
        }
    }

    private fun monitoringOwnsForegroundType(): Boolean =
        session != null || liveCameraHost?.active == true || when (runtime.monitoring.state.value.status) {
        MonitoringStatus.STARTING,
        MonitoringStatus.RUNNING,
        MonitoringStatus.RESTARTING,
        MonitoringStatus.STOPPING,
        -> true
        MonitoringStatus.STOPPED,
        MonitoringStatus.ERROR,
        -> false
    }

    private fun connectivityNotification() = baseNotificationBuilder()
        .setContentTitle(getString(R.string.device_runtime_notification_title))
        .setContentText(getString(R.string.device_runtime_notification_connected))
        .build()

    private fun initialMonitoringNotification() = monitoringNotificationBuilder()
        .setContentTitle(getString(R.string.monitoring_notification_title))
        .setContentText(getString(R.string.monitoring_notification_starting))
        .build()

    private fun liveNotification() = baseNotificationBuilder()
        .setContentTitle(getString(R.string.live_view_notification_title))
        .setContentText(getString(R.string.live_view_notification_active))
        .build()

    private fun baseNotificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, getString(R.string.monitoring_notification_open), openAppPendingIntent())

    private fun monitoringNotificationBuilder(): NotificationCompat.Builder = baseNotificationBuilder()
        .addAction(0, getString(R.string.monitoring_notification_stop), stopMonitoringPendingIntent())

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopMonitoringPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP_MONITORING,
        intent(this, ACTION_STOP_MONITORING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureForeground(notification: Notification, monitoringActive: Boolean) {
        if (foregroundStarted) return
        publishForeground(notification, monitoringActive)
    }

    private fun publishForeground(notification: Notification, monitoringActive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes(monitoringActive))
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun foregroundServiceTypes(monitoringActive: Boolean): Int {
        var types = if (connectivityRequested) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        if (monitoringActive) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            val audioEnabled = SharedPreferencesRecordingSettingsRepository(applicationContext).load().audioEnabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        check(types != 0) { "Foreground service has no active runtime ownership" }
        return types
    }

    @Suppress("DEPRECATION")
    private fun removeForegroundAndStop() {
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        foregroundStarted = false
        lastNotificationFingerprint = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.device_runtime_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.device_runtime_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START_CONNECTIVITY = "com.ashraffarag.sentricam.device.START_CONNECTIVITY"
        const val ACTION_STOP_CONNECTIVITY = "com.ashraffarag.sentricam.device.STOP_CONNECTIVITY"
        const val ACTION_START_MONITORING = "com.ashraffarag.sentricam.monitoring.START"
        const val ACTION_STOP_MONITORING = "com.ashraffarag.sentricam.monitoring.STOP"
        const val ACTION_RESTART_MONITORING = "com.ashraffarag.sentricam.monitoring.RESTART"
        const val ACTION_RESTORE_RUNTIME = "com.ashraffarag.sentricam.device.RESTORE_RUNTIME"
        const val CHANNEL_ID = "sentricam_monitoring"
        const val NOTIFICATION_ID = 2101
        private const val REQUEST_OPEN_APP = 2102
        private const val REQUEST_STOP_MONITORING = 2103
        private const val TAG = "DeviceConnectivity"

        fun intent(context: Context, action: String) =
            Intent(context, DeviceConnectivityService::class.java).setAction(action)
    }
}
