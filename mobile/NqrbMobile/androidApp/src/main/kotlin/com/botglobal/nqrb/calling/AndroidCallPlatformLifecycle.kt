package com.botglobal.nqrb.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import androidx.core.content.ContextCompat
import com.botglobal.mobile.platform.calling.CallAudioRoute
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallPlatformAction
import com.botglobal.mobile.platform.calling.CallPlatformLifecycle
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.nqrb.MainActivity
import com.botglobal.nqrb.NqrbApplication
import com.botglobal.nqrb.R
import com.botglobal.nqrb.app.state.NqrbRingtoneSettings
import com.botglobal.mobile.platform.preferences.AndroidPreferenceStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class AndroidCallPlatformLifecycle(
    private val application: NqrbApplication,
) : CallPlatformLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableActions = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 8)
    override val actions = mutableActions.asSharedFlow()
    private val ended = AtomicBoolean(true)
    private var control: CallControlScope? = null
    private var callsManager: CallsManager? = null
    private var endpointSnapshot: List<CallEndpointCompat> = emptyList()
    private var currentRouteSnapshot = CallAudioRoute.System
    private val incomingRingtone = NqrbRingtonePlayer(application)
    private var ringbackPlayer: MediaPlayer? = null
    private var currentDirection: CallDirection? = null
    private var connectedFeedbackGiven = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callsManager = CallsManager(application).also {
                it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            }
        }
    }

    override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) {
        try {
            startPlatformCall(participant, direction)
        } catch (error: Throwable) {
            Log.e(LogTag, "Android call setup failed type=${error::class.simpleName}", error)
            throw error
        }
    }

    override suspend fun presentIncoming(callId: CallId, participant: CallParticipant) {
        try {
            currentDirection = CallDirection.Incoming
            connectedFeedbackGiven = false
            ended.set(false)
            val notificationVisible = runCatching {
                NqrbIncomingCallNotification.show(application, callId, participant.displayName)
            }.onFailure { error ->
                Log.w(LogTag, "incoming notification skipped type=${error::class.simpleName}")
            }.getOrDefault(false)
            if (notificationVisible) {
                val setting = NqrbRingtoneSettings(AndroidPreferenceStore(application, NqrbOngoingCallService.RingtonePreferences))
                runCatching {
                    incomingRingtone.play(setting.selection.value, looping = true, deviceToneUri = setting.deviceToneUri)
                }.onFailure { error ->
                    Log.w(LogTag, "incoming ringtone skipped type=${error::class.simpleName}")
                }
            }
            registerOptionalTelecomCall(participant, CallDirection.Incoming)
        } catch (error: Throwable) {
            Log.e(LogTag, "Android incoming call presentation failed type=${error::class.simpleName}", error)
            incomingRingtone.stop()
            NqrbIncomingCallNotification.clear(application)
            throw error
        }
    }

    private suspend fun startPlatformCall(participant: CallParticipant, direction: CallDirection) {
        ended.set(false)
        currentDirection = direction
        connectedFeedbackGiven = false
        setRingback(false)
        incomingRingtone.stop()
        NqrbOngoingCallService.start(application, participant.displayName)
        NqrbIncomingCallNotification.clear(application)
        if (direction == CallDirection.Incoming && control != null) return
        registerOptionalTelecomCall(participant, direction)
    }

    private suspend fun registerOptionalTelecomCall(participant: CallParticipant, direction: CallDirection) {
        try {
            startTelecomCall(participant, direction)
        } catch (timeout: TimeoutCancellationException) {
            Log.w(LogTag, "telecom ${direction.name.lowercase()} call timed out; in-app call remains available")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The foreground service, notification and in-app answer work without Telecom.
            Log.w(LogTag, "telecom ${direction.name.lowercase()} call unavailable type=${error::class.simpleName}")
        }
    }

    private suspend fun startTelecomCall(participant: CallParticipant, direction: CallDirection) {
        // API 24/25 has no self-managed Telecom call API. The foreground call service
        // and the NQRB signaling controller still support a basic voice call.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ready = CompletableDeferred<Unit>()
        val telecomJob = scope.launch {
            runCatching {
                Log.i(LogTag, "telecom registration requested direction=${direction.name.lowercase()}")
                requireNotNull(callsManager).addCall(
                    CallAttributesCompat(
                        displayName = participant.displayName,
                        address = Uri.fromParts("nqrb", "call", null),
                        direction = if (direction == CallDirection.Outgoing) {
                            CallAttributesCompat.DIRECTION_OUTGOING
                        } else CallAttributesCompat.DIRECTION_INCOMING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        callCapabilities = 0,
                    ),
                    onAnswer = {
                        Log.i(LogTag, "telecom answer requested")
                        mutableActions.emit(CallPlatformAction.Answer)
                    },
                    onDisconnect = {
                        Log.i(LogTag, "telecom disconnect code=${it.code}")
                        mutableActions.emit(if (it.code == DisconnectCause.REJECTED) CallPlatformAction.Reject else CallPlatformAction.End)
                        if (!ready.isCompleted) ready.complete(Unit)
                    },
                    onSetActive = { },
                    // Telecom may report inactive while an incoming call is still ringing.
                    // Termination is authoritative only through disconnect/reject.
                    onSetInactive = { },
                ) {
                    control = this
                    Log.i(LogTag, "telecom call control acquired")
                    if (!ready.isCompleted) ready.complete(Unit)
                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            currentRouteSnapshot = endpoint.toDomainRoute()
                            mutableActions.emit(CallPlatformAction.RouteChanged(currentRouteSnapshot))
                        }
                    }
                    launch {
                        availableEndpoints.collect { endpoints ->
                            endpointSnapshot = endpoints
                            val routes = endpoints.mapTo(linkedSetOf()) { it.toDomainRoute() }
                            Log.i(LogTag, "telecom routes available=${routes.joinToString { it.name.lowercase() }}")
                            mutableActions.emit(
                                CallPlatformAction.AvailableRoutesChanged(routes),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(LogTag, "telecom call scope failed type=${error::class.simpleName}")
                if (!ready.isCompleted) ready.completeExceptionally(error)
            }
        }
        try {
            withTimeout(5_000) { ready.await() }
        } catch (error: Throwable) {
            telecomJob.cancel()
            throw error
        }
    }

    override suspend fun markActive() {
        if (ended.get()) return
        setRingback(false)
        if (currentDirection == CallDirection.Outgoing && !connectedFeedbackGiven) {
            connectedFeedbackGiven = true
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    application.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    application.getSystemService(Vibrator::class.java)
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) control?.setActive()
        NqrbOngoingCallService.markActive(application)
    }

    override fun setRingback(active: Boolean) {
        if (!active) {
            ringbackPlayer?.runCatching { stop() }
            ringbackPlayer?.release()
            ringbackPlayer = null
            return
        }
        if (ended.get() || ringbackPlayer != null) return
        runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            MediaPlayer.create(application, R.raw.nqrb_ringback, attributes, 0)?.also {
                it.isLooping = true
                it.start()
                ringbackPlayer = it
            }
        }.onFailure { Log.w(LogTag, "ringback unavailable type=${it::class.simpleName}") }
    }

    override suspend fun requestRoute(route: CallAudioRoute): CallAudioRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CallAudioRoute.System
        val callControl = control ?: return CallAudioRoute.System
        val endpoint = endpointSnapshot.firstOrNull { it.toDomainRoute() == route } ?: run {
            Log.i(LogTag, "telecom route unavailable requested=${route.name.lowercase()} current=${currentRouteSnapshot.name.lowercase()}")
            return currentRouteSnapshot
        }
        val result = runCatching { callControl.requestEndpointChange(endpoint) }.getOrNull()
        if (result !is CallControlResult.Success) {
            Log.i(LogTag, "telecom route request rejected requested=${route.name.lowercase()}")
            return currentRouteSnapshot
        }
        val applied = withTimeoutOrNull(1_500) {
            callControl.currentCallEndpoint.first { it.toDomainRoute() == route }.toDomainRoute()
        } ?: currentRouteSnapshot
        Log.i(LogTag, "telecom route applied=${applied.name.lowercase()}")
        return applied
    }

    override suspend fun end(reason: CallTerminationReason) {
        setRingback(false)
        if (!ended.compareAndSet(false, true)) return
        incomingRingtone.stop()
        Log.i(LogTag, "telecom disconnect requested reason=${reason.name.lowercase()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cause = when (reason) {
                CallTerminationReason.Rejected -> DisconnectCause.REJECTED
                CallTerminationReason.Busy -> DisconnectCause.BUSY
                CallTerminationReason.Remote -> DisconnectCause.REMOTE
                CallTerminationReason.Failed -> DisconnectCause.ERROR
                CallTerminationReason.Local -> DisconnectCause.LOCAL
                CallTerminationReason.Cancelled, CallTerminationReason.Missed, CallTerminationReason.Expired -> DisconnectCause.CANCELED
            }
            runCatching { control?.disconnect(DisconnectCause(cause)) }
        }
        control = null
        endpointSnapshot = emptyList()
        currentRouteSnapshot = CallAudioRoute.System
        currentDirection = null
        mutableActions.tryEmit(CallPlatformAction.AvailableRoutesChanged(emptySet()))
        NqrbIncomingCallNotification.clear(application)
        NqrbOngoingCallService.stop(application)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun CallEndpointCompat.toDomainRoute(): CallAudioRoute = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> CallAudioRoute.Earpiece
        CallEndpointCompat.TYPE_SPEAKER -> CallAudioRoute.Speaker
        CallEndpointCompat.TYPE_WIRED_HEADSET -> CallAudioRoute.WiredHeadset
        CallEndpointCompat.TYPE_BLUETOOTH -> CallAudioRoute.Bluetooth
        else -> CallAudioRoute.System
    }

    private companion object {
        const val LogTag = "NqrbCalling"
    }
}

private object NqrbIncomingCallNotification {
    fun show(context: Context, callId: CallId, displayName: String): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createNotificationChannel(context, notificationManager)
        if (!notificationManager.areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                notificationManager.getNotificationChannel(NqrbOngoingCallService.IncomingChannelId)?.importance == NotificationManager.IMPORTANCE_NONE)
        ) return false
        notificationManager.notify(
            NqrbOngoingCallService.IncomingNotificationId,
            incomingNotification(context, callId, displayName),
        )
        return true
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NqrbOngoingCallService.IncomingNotificationId)
    }

    private fun incomingNotification(context: Context, callId: CallId, displayName: String): Notification {
        val openIntent = incomingCallActivity(context, 10)
        val fullScreenIntent = incomingCallActivity(context, 13)
        val answer = callAction(context, NqrbOngoingCallService.ActionAnswerCall, callId, 11)
        val reject = callAction(context, NqrbOngoingCallService.ActionRejectCall, callId, 12)
        val builder = notificationBuilder(context, NqrbOngoingCallService.IncomingChannelId)
            .setSmallIcon(R.drawable.ic_nqrb_notification)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(displayName)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenIntent, true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reject_call), reject).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.answer_call), answer).build())
        return builder.build()
    }

    private fun incomingCallActivity(context: Context, requestCode: Int) = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NqrbOngoingCallService.ExtraShowOverLockScreen, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun callAction(context: Context, action: String, callId: CallId, requestCode: Int) = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, NqrbCallActionReceiver::class.java)
            .setAction(action)
            .putExtra(NqrbOngoingCallService.ExtraCallId, callId.value),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun notificationBuilder(context: Context, channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }

    private fun createNotificationChannel(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NqrbOngoingCallService.IncomingChannelId,
                    context.getString(R.string.incoming_call_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.incoming_call_channel_description)
                    setSound(null, null)
                    enableVibration(true)
                },
            )
        }
    }
}

class NqrbOngoingCallService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val ringtonePlayer by lazy { NqrbRingtonePlayer(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action !in setOf(ActionStart, ActionActive)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val displayName = intent?.getStringExtra(ExtraDisplayName).orEmpty().ifBlank {
            getSharedPreferences(Preferences, MODE_PRIVATE).getString(ExtraDisplayName, null) ?: getString(R.string.app_name)
        }
        getSharedPreferences(Preferences, MODE_PRIVATE).edit().putString(ExtraDisplayName, displayName).apply()
        val incoming = intent?.getBooleanExtra(ExtraIncoming, false) == true && action == ActionStart
        startForeground(NotificationId, if (incoming) incomingNotification(displayName) else ongoingNotification(displayName))
        if (incoming && notificationManager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                notificationManager.getNotificationChannel(IncomingChannelId)?.importance != NotificationManager.IMPORTANCE_NONE)
        ) {
            val setting = NqrbRingtoneSettings(AndroidPreferenceStore(this, RingtonePreferences))
            ringtonePlayer.play(setting.selection.value, looping = true, deviceToneUri = setting.deviceToneUri)
        } else {
            ringtonePlayer.stop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ringtonePlayer.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun ongoingNotification(displayName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NqrbCallActionReceiver::class.java).setAction(ActionEndCall),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = notificationBuilder(ChannelId)
            .setSmallIcon(R.drawable.ic_nqrb_notification)
            .setContentTitle(getString(R.string.ongoing_call_title))
            .setContentText(displayName)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    Person.Builder().setName(displayName).setImportant(true).build(),
                    endIntent,
                ),
            )
        } else {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.end_call), endIntent).build())
        }
        return builder.build()
    }

    private fun incomingNotification(displayName: String): Notification {
        val answer = callAction(ActionAnswerCall, 2)
        val reject = callAction(ActionRejectCall, 3)
        val builder = notificationBuilder(IncomingChannelId)
            .setSmallIcon(R.drawable.ic_nqrb_notification)
            .setContentTitle(getString(R.string.incoming_call_title))
            .setContentText(displayName)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(
                Person.Builder().setName(displayName).setImportant(true).build(), reject, answer))
        } else {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.reject_call), reject).build())
            builder.addAction(Notification.Action.Builder(null, getString(R.string.answer_call), answer).build())
        }
        return builder.build()
    }

    private fun callAction(action: String, requestCode: Int) = PendingIntent.getBroadcast(
        this, requestCode, Intent(this, NqrbCallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun notificationBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    getString(R.string.ongoing_call_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = getString(R.string.ongoing_call_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            notificationManager.createNotificationChannel(NotificationChannel(
                IncomingChannelId, getString(R.string.incoming_call_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.incoming_call_channel_description)
                setSound(null, null)
                enableVibration(true)
            })
        }
    }

    companion object {
        const val ChannelId = "nqrb_ongoing_calls"
        const val NotificationId = 2101
        const val IncomingNotificationId = 2102
        const val ActionEndCall = "com.botglobal.nqrb.action.END_CALL"
        const val ActionAnswerCall = "com.botglobal.nqrb.action.ANSWER_CALL"
        const val ActionRejectCall = "com.botglobal.nqrb.action.REJECT_CALL"
        const val IncomingChannelId = "nqrb_incoming_calls_app_tone"
        const val ExtraCallId = "call_id"
        const val ExtraShowOverLockScreen = "show_over_lock_screen"
        const val RingtonePreferences = "nqrb_ringtone"
        private const val ActionStart = "com.botglobal.nqrb.action.START_ONGOING_CALL"
        private const val ActionActive = "com.botglobal.nqrb.action.ACTIVE_CALL"
        private const val ExtraDisplayName = "display_name"
        private const val ExtraIncoming = "incoming"
        private const val Preferences = "nqrb_call_presentation"

        fun start(context: Context, displayName: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NqrbOngoingCallService::class.java)
                    .setAction(ActionStart)
                    .putExtra(ExtraDisplayName, displayName)
                    .putExtra(ExtraIncoming, false),
            )
        }

        fun markActive(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NqrbOngoingCallService::class.java).setAction(ActionActive))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NqrbOngoingCallService::class.java))
        }

        fun clearStoredPresentation(context: Context) {
            context.getSharedPreferences(Preferences, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

class NqrbCallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(NqrbOngoingCallService.ActionEndCall, NqrbOngoingCallService.ActionAnswerCall, NqrbOngoingCallService.ActionRejectCall)) return
        val pending = goAsync()
        val application = context.applicationContext as NqrbApplication
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                when (intent.action) {
                    NqrbOngoingCallService.ActionAnswerCall -> application.callRuntime.session.acceptIncoming()
                    NqrbOngoingCallService.ActionRejectCall -> application.callRuntime.session.rejectIncoming()
                    else -> application.callRuntime.session.end(CallTerminationReason.Local)
                }
            }
            pending.finish()
        }
    }
}
