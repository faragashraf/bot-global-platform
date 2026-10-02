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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

interface FirebaseMessagingRuntimeOwner {
    val firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
}

data class FirebaseMessageDeliveryPolicy(
    val blockOnMessage: Boolean = false,
    val blockTimeoutMillis: Long = 20_000L,
)

class AndroidFirebaseMessagingRuntime(
    context: Context,
    registrationController: PushRegistrationController,
    private val messageHandler: PushMessageHandler = IgnorePushMessages,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    messageDeliveryPolicy: FirebaseMessageDeliveryPolicy = FirebaseMessageDeliveryPolicy(),
) : PushRegistrationLifecycle {
    init {
        AndroidFirebaseBootstrap.ensureInitialized(context.applicationContext)
    }

    private val dispatcher = FirebasePushMessageDispatcher(
        handler = messageHandler,
        scope = scope,
        policy = messageDeliveryPolicy,
        logger = { message, error ->
            if (error == null) Log.i(LOG_TAG, message) else Log.w(LOG_TAG, message, error)
        },
    )

    private val coordinator = FirebaseRegistrationCoordinator(
        controller = registrationController,
        store = AndroidFirebaseDestinationStore(context.applicationContext),
        client = AndroidFirebaseRegistrationClient(),
    )

    override suspend fun activate() {
        coordinator.activate()
    }

    override suspend fun deactivate() = coordinator.deactivate()

    override suspend fun clearLocalState() = coordinator.clearLocalState()

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
        dispatcher.dispatch(safeMessage)
    }

    private companion object {
        const val LOG_TAG = "BotGlobalPush"
    }
}

internal class FirebasePushMessageDispatcher(
    private val handler: PushMessageHandler,
    private val scope: CoroutineScope,
    private val policy: FirebaseMessageDeliveryPolicy,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    fun dispatch(message: PushMessage) {
        if (policy.blockOnMessage) {
            runBlocking(Dispatchers.IO) {
                runCatching {
                    withTimeout(policy.blockTimeoutMillis) { handler.onMessage(message) }
                }.onFailure { error ->
                    logger("FCM message handler failed before callback return type=${error::class.simpleName}", error)
                }
            }
        } else {
            logger("FCM message handed off to the shared push handler.", null)
            scope.launch {
                runCatching { handler.onMessage(message) }.onFailure { error ->
                    logger("FCM message handler failed asynchronously type=${error::class.simpleName}", error)
                }
            }
        }
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
