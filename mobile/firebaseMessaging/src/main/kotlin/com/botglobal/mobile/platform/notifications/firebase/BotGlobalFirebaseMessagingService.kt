package com.botglobal.mobile.platform.notifications.firebase

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BotGlobalFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(fid: String) {
        runtime()?.onRegistered(fid)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        runtime()?.onRegistered(token)
    }

    override fun onUnregistered(fid: String) {
        runtime()?.onUnregistered()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runtime()?.onMessageReceived(message)
    }

    private fun runtime(): AndroidFirebaseMessagingRuntime? =
        (application as? FirebaseMessagingRuntimeOwner)?.firebaseMessagingRuntime
}
