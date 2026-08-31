package com.botglobal.mobile.platform.notifications

import kotlin.jvm.JvmInline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SemanticNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val createdAtUtc: String,
    val deepLink: String?,
    val isRead: Boolean,
)

interface NotificationInbox {
    suspend fun list(): List<SemanticNotification>
    suspend fun store(notification: SemanticNotification)
    suspend fun markRead(id: String)
    suspend fun unreadCount(): Int
}

@JvmInline
value class OpaquePushDestinationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Push destination identifier is required." }
    }

    override fun toString(): String = "<redacted>"
}

data class PushDestination(
    val provider: String,
    val identifier: OpaquePushDestinationId,
) {
    init {
        require(provider.isNotBlank()) { "Push provider is required." }
    }
}

enum class PushRegistrationOutcome {
    Registered,
    Unregistered,
    AuthenticationRequired,
    Rejected,
    RetryableFailure,
}

interface PushRegistration {
    suspend fun register(destination: PushDestination): PushRegistrationOutcome
    suspend fun unregister(): PushRegistrationOutcome
}

data class PushDeviceInstallation(
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String?,
) {
    override fun toString(): String =
        "PushDeviceInstallation(installationId=<redacted>, platform=$platform, deviceName=<redacted>, appVersion=$appVersion)"
}

data class MobileDeviceCredential(
    val deviceId: String,
    val credential: String,
) {
    override fun toString(): String = "MobileDeviceCredential(deviceId=<redacted>, credential=<redacted>)"
}

interface MobileDeviceCredentialVault {
    suspend fun restore(): MobileDeviceCredential?
    suspend fun save(credential: MobileDeviceCredential)
    suspend fun clear()
}

interface PushRegistrationLifecycle {
    suspend fun activate()
    suspend fun deactivate()
}

object UnavailablePushRegistrationLifecycle : PushRegistrationLifecycle {
    override suspend fun activate() = Unit
    override suspend fun deactivate() = Unit
}

data class PushMessage(
    val messageId: String?,
    val data: Map<String, String>,
    val sentAtEpochMilliseconds: Long,
    val timeToLiveSeconds: Int,
)

fun interface PushMessageHandler {
    suspend fun onMessage(message: PushMessage)
}

object IgnorePushMessages : PushMessageHandler {
    override suspend fun onMessage(message: PushMessage) = Unit
}

class PushRegistrationController(
    private val registration: PushRegistration,
) : PushRegistrationLifecycle {
    private val mutex = Mutex()
    private var active = false
    private var availableDestination: PushDestination? = null
    private var registeredDestination: PushDestination? = null

    override suspend fun activate() {
        mutex.withLock {
            active = true
            registerAvailableDestination()
        }
    }

    suspend fun destinationAvailable(destination: PushDestination) {
        mutex.withLock {
            availableDestination = destination
            registerAvailableDestination()
        }
    }

    suspend fun destinationUnavailable() {
        mutex.withLock {
            availableDestination = null
            registeredDestination = null
        }
    }

    override suspend fun deactivate() {
        mutex.withLock {
            val wasActive = active
            active = false
            availableDestination = null
            registeredDestination = null
            if (wasActive) {
                registration.unregister()
            }
        }
    }

    private suspend fun registerAvailableDestination() {
        val destination = availableDestination ?: return
        if (!active || destination == registeredDestination) return
        if (registration.register(destination) == PushRegistrationOutcome.Registered) {
            registeredDestination = destination
        }
    }
}

interface ForegroundNotificationRouter {
    suspend fun onNotification(notification: SemanticNotification)
}
