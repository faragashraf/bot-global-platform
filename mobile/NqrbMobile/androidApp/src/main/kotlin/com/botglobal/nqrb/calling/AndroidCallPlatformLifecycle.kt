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
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AndroidCallPlatformLifecycle(
    private val application: NqrbApplication,
) : CallPlatformLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableActions = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 8)
    override val actions = mutableActions.asSharedFlow()
    private val ended = AtomicBoolean(true)
    private var control: CallControlScope? = null
    private var callsManager: CallsManager? = null

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

    private suspend fun startPlatformCall(participant: CallParticipant, direction: CallDirection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw UnsupportedOperationException("NQRB calls require Android 8.0 or newer.")
        }
        ended.set(false)
        NqrbOngoingCallService.start(application, participant.displayName, direction)
        val ready = CompletableDeferred<Unit>()
        scope.launch {
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
                            mutableActions.emit(CallPlatformAction.RouteChanged(endpoint.toDomainRoute()))
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(LogTag, "telecom call scope failed type=${error::class.simpleName}")
                if (!ready.isCompleted) ready.completeExceptionally(error)
            }
        }
        withTimeout(5_000) { ready.await() }
    }

    override suspend fun markActive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) control?.setActive()
        NqrbOngoingCallService.markActive(application)
    }

    override suspend fun requestRoute(route: CallAudioRoute): CallAudioRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CallAudioRoute.System
        val callControl = control ?: return CallAudioRoute.System
        val endpoint = callControl.availableEndpoints.first().firstOrNull { it.toDomainRoute() == route }
            ?: return callControl.currentCallEndpoint.first().toDomainRoute()
        callControl.requestEndpointChange(endpoint)
        return endpoint.toDomainRoute()
    }

    override suspend fun end(reason: CallTerminationReason) {
        if (!ended.compareAndSet(false, true)) return
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

class NqrbOngoingCallService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

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
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
        val builder = Notification.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_nqrb_launcher)
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
        val builder = Notification.Builder(this, IncomingChannelId)
            .setSmallIcon(R.drawable.ic_nqrb_launcher)
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
                enableVibration(true)
            })
        }
    }

    companion object {
        const val ChannelId = "nqrb_ongoing_calls"
        const val NotificationId = 2101
        const val ActionEndCall = "com.botglobal.nqrb.action.END_CALL"
        const val ActionAnswerCall = "com.botglobal.nqrb.action.ANSWER_CALL"
        const val ActionRejectCall = "com.botglobal.nqrb.action.REJECT_CALL"
        const val IncomingChannelId = "nqrb_incoming_calls"
        private const val ActionStart = "com.botglobal.nqrb.action.START_ONGOING_CALL"
        private const val ActionActive = "com.botglobal.nqrb.action.ACTIVE_CALL"
        private const val ExtraDisplayName = "display_name"
        private const val ExtraIncoming = "incoming"
        private const val Preferences = "nqrb_call_presentation"

        fun start(context: Context, displayName: String, direction: CallDirection) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NqrbOngoingCallService::class.java)
                    .setAction(ActionStart)
                    .putExtra(ExtraDisplayName, displayName)
                    .putExtra(ExtraIncoming, direction == CallDirection.Incoming),
            )
        }

        fun markActive(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NqrbOngoingCallService::class.java).setAction(ActionActive))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NqrbOngoingCallService::class.java))
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
