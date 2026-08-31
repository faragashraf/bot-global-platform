package com.botglobal.mobile.platform.notifications.firebase

import com.botglobal.mobile.platform.notifications.OpaquePushDestinationId
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle

internal interface FirebaseDestinationStore {
    fun read(): String?
    fun write(identifier: String)
    fun clear()
}

internal interface FirebaseRegistrationClient {
    suspend fun register()
    suspend fun unregister()
}

internal class FirebaseRegistrationCoordinator(
    private val controller: PushRegistrationController,
    private val store: FirebaseDestinationStore,
    private val client: FirebaseRegistrationClient,
) : PushRegistrationLifecycle {
    override suspend fun activate() {
        controller.activate()
        store.read()
            ?.takeIf(String::isNotBlank)
            ?.let { controller.destinationAvailable(it.asDestination()) }
        client.register()
    }

    override suspend fun deactivate() {
        controller.deactivate()
        try {
            client.unregister()
        } finally {
            store.clear()
            controller.destinationUnavailable()
        }
    }

    suspend fun onRegistered(identifier: String) {
        if (identifier.isBlank()) return
        store.write(identifier)
        controller.destinationAvailable(identifier.asDestination())
    }

    suspend fun onUnregistered() {
        store.clear()
        controller.destinationUnavailable()
    }

    private fun String.asDestination() = PushDestination(
        provider = FIREBASE_PROVIDER,
        identifier = OpaquePushDestinationId(this),
    )

    private companion object {
        const val FIREBASE_PROVIDER = "fcm"
    }
}
