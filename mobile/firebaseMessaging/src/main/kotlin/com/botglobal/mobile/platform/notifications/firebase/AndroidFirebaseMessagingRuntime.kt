package com.botglobal.mobile.platform.notifications.firebase

import android.content.Context
import android.util.Log
import com.botglobal.mobile.platform.notifications.IgnorePushMessages
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.google.android.gms.tasks.Task
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

private class AndroidFirebaseDestinationStore(
    context: Context,
) : FirebaseDestinationStore {
    private val preferences = context.getSharedPreferences(
        "botglobal_firebase_push_destination",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(KEY_IDENTIFIER, null)

    override fun write(identifier: String) {
        preferences.edit().putString(KEY_IDENTIFIER, identifier).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_IDENTIFIER).apply()
    }

    private companion object {
        const val KEY_IDENTIFIER = "registration_identifier"
    }
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
