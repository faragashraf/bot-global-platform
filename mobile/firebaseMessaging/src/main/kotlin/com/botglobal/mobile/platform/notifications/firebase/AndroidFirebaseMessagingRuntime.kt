package com.botglobal.mobile.platform.notifications.firebase

import android.content.Context
import android.util.Log
import com.botglobal.mobile.platform.notifications.IgnorePushMessages
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

interface FirebaseMessagingRuntimeOwner {
    val firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
}

class AndroidFirebaseMessagingRuntime(
    context: Context,
    registrationController: PushRegistrationController,
    private val messageHandler: PushMessageHandler = IgnorePushMessages,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PushRegistrationLifecycle {
    init {
        AndroidFirebaseBootstrap.ensureInitialized(context.applicationContext)
    }

    private val coordinator = FirebaseRegistrationCoordinator(
        controller = registrationController,
        store = AndroidFirebaseDestinationStore(context.applicationContext),
        client = AndroidFirebaseRegistrationClient(),
    )

    override suspend fun activate() {
        coordinator.activate()
    }

    override suspend fun deactivate() {
        coordinator.deactivate()
    }

    internal fun onRegistered(identifier: String) {
        scope.launch { coordinator.onRegistered(identifier) }
    }

    internal fun onUnregistered() {
        scope.launch { coordinator.onUnregistered() }
    }

    internal fun onMessageReceived(message: RemoteMessage) {
        val safeMessage = PushMessage(
            messageId = message.messageId,
            data = message.data.toMap(),
            sentAtEpochMilliseconds = message.sentTime,
            timeToLiveSeconds = message.ttl,
        )
        Log.i(LOG_TAG, "FCM message handed off to the shared push handler.")
        scope.launch { messageHandler.onMessage(safeMessage) }
    }

    private companion object {
        const val LOG_TAG = "BotGlobalPush"
    }
}

object AndroidFirebaseBootstrap {
    fun ensureInitialized(context: Context): FirebaseApp =
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context)
            ?: error("Firebase configuration is unavailable for this application.")
}

private class AndroidFirebaseRegistrationClient : FirebaseRegistrationClient {
    override suspend fun register() {
        FirebaseMessaging.getInstance().register().awaitCompletion()
    }

    override suspend fun unregister() {
        FirebaseMessaging.getInstance().unregister().awaitCompletion()
    }
}

private suspend fun <T> Task<T>.awaitCompletion(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(
                task.exception ?: IllegalStateException("Firebase operation failed."),
            )
        }
    }
}
