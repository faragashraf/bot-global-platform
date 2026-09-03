package com.botglobal.mobile.platform.notifications

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SemanticNotificationPriority {
    Normal,
    High,
}

sealed interface SemanticNotificationDestination {
    data class Internal(val route: String) : SemanticNotificationDestination
    data class ExternalHttps(val url: String) : SemanticNotificationDestination
}

data class SemanticNotification(
    val id: String,
    val type: String,
    val titleAr: String,
    val titleEn: String,
    val bodyAr: String,
    val bodyEn: String,
    val createdAtUtc: String,
    val priority: SemanticNotificationPriority,
    val destination: SemanticNotificationDestination?,
    val soundKey: String?,
    val isRead: Boolean,
) {
    override fun toString(): String =
        "SemanticNotification(id=$id,type=$type,content=<redacted>,createdAtUtc=$createdAtUtc," +
            "priority=$priority,destination=<redacted>,soundKey=$soundKey,isRead=$isRead)"
}

enum class NotificationStoreOutcome {
    Added,
    Duplicate,
}

interface NotificationInbox {
    val notifications: StateFlow<List<SemanticNotification>>

    suspend fun list(): List<SemanticNotification>
    suspend fun store(notification: SemanticNotification): NotificationStoreOutcome
    suspend fun markRead(id: String)
    suspend fun markAllRead()
    suspend fun unreadCount(): Int
}

class InMemoryNotificationInbox(
    initialNotifications: List<SemanticNotification> = emptyList(),
) : NotificationInbox {
    private val mutex = Mutex()
    private val mutableNotifications = MutableStateFlow(initialNotifications)
    override val notifications: StateFlow<List<SemanticNotification>> =
        mutableNotifications.asStateFlow()

    override suspend fun list(): List<SemanticNotification> = mutex.withLock {
        mutableNotifications.value
    }

    override suspend fun store(notification: SemanticNotification): NotificationStoreOutcome =
        mutex.withLock {
            if (mutableNotifications.value.any { it.id == notification.id }) {
                NotificationStoreOutcome.Duplicate
            } else {
                mutableNotifications.value = listOf(notification.copy(isRead = false)) +
                    mutableNotifications.value
                NotificationStoreOutcome.Added
            }
        }

    override suspend fun markRead(id: String) {
        mutex.withLock {
            mutableNotifications.value = mutableNotifications.value.map {
                if (it.id == id) it.copy(isRead = true) else it
            }
        }
    }

    override suspend fun markAllRead() {
        mutex.withLock {
            mutableNotifications.value = mutableNotifications.value.map { it.copy(isRead = true) }
        }
    }

    override suspend fun unreadCount(): Int = mutex.withLock {
        mutableNotifications.value.count { !it.isRead }
    }
}

class HttpsHostAllowlist(hosts: Set<String>) {
    private val allowedHosts = hosts.map(String::lowercase).toSet().also {
        require(it.isNotEmpty()) { "At least one HTTPS host is required." }
        require(it.none(String::isBlank)) { "HTTPS hosts cannot be blank." }
    }

    fun validated(value: String?): String? {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (candidate.length > 2_048 || candidate.any { it.code <= 0x20 } || '\\' in candidate) {
            return null
        }
        if (!candidate.startsWith(HTTPS_SCHEME, ignoreCase = true)) return null
        val authority = candidate.substring(HTTPS_SCHEME.length).substringBeforeAny('/', '?', '#')
        if (authority.isEmpty() || '@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        return candidate.takeIf { host in allowedHosts && (port.isEmpty() || port == "443") }
    }

    private companion object {
        const val HTTPS_SCHEME = "https://"
    }
}

class SemanticPushEnvelopeParser(
    private val externalLinks: HttpsHostAllowlist,
    private val internalDestination: (String) -> SemanticNotificationDestination.Internal? = { null },
) {
    fun parse(message: PushMessage): SemanticNotification? {
        val data = message.data
        val id = data["notificationId"]?.trim()?.takeIf(::isSemanticNotificationId) ?: return null
        val rawInternal = data["destination"]?.trim()?.takeIf(String::isNotEmpty)
        val rawExternal = data["actionUrl"]?.trim()?.takeIf(String::isNotEmpty)
        val destination = rawInternal?.let(internalDestination)
            ?: externalLinks.validated(rawExternal)?.let(SemanticNotificationDestination::ExternalHttps)
        return SemanticNotification(
            id = id,
            type = data["type"]?.trim()?.takeIf(String::isNotEmpty) ?: "general",
            titleAr = data["titleAr"].orEmpty(),
            titleEn = data["titleEn"] ?: data["title"].orEmpty(),
            bodyAr = data["bodyAr"].orEmpty(),
            bodyEn = data["bodyEn"] ?: data["body"].orEmpty(),
            createdAtUtc = data["createdAtUtc"]?.takeIf(String::isNotBlank)
                ?: message.sentAtEpochMilliseconds.takeIf { it > 0 }
                    ?.let { Instant.fromEpochMilliseconds(it).toString() }
                    .orEmpty(),
            priority = semanticNotificationPriority(data["priority"]),
            destination = destination,
            soundKey = data["sound"]?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
            isRead = false,
        )
    }
}

fun semanticNotificationPriority(value: String?): SemanticNotificationPriority =
    when (value?.trim()?.lowercase()) {
        "2", "high" -> SemanticNotificationPriority.High
        else -> SemanticNotificationPriority.Normal
    }

fun isSemanticNotificationId(value: String?): Boolean {
    val candidate = value?.trim() ?: return false
    return COMPACT_NOTIFICATION_ID.matches(candidate) || HYPHENATED_NOTIFICATION_ID.matches(candidate)
}

enum class NotificationPresentationOutcome {
    Presented,
    Disabled,
    PermissionDenied,
    Unavailable,
}

fun interface NotificationPresenter {
    suspend fun present(notification: SemanticNotification): NotificationPresentationOutcome
}

object IgnoreNotificationPresentation : NotificationPresenter {
    override suspend fun present(notification: SemanticNotification) =
        NotificationPresentationOutcome.Disabled
}

class SemanticPushMessageHandler(
    private val parser: SemanticPushEnvelopeParser,
    private val inbox: NotificationInbox,
    private val presenter: NotificationPresenter = IgnoreNotificationPresentation,
) : PushMessageHandler {
    override suspend fun onMessage(message: PushMessage) {
        val notification = parser.parse(message) ?: return
        if (inbox.store(notification) == NotificationStoreOutcome.Added) {
            presenter.present(notification)
        }
    }
}

private val COMPACT_NOTIFICATION_ID = Regex("^[0-9a-fA-F]{32}$")
private val HYPHENATED_NOTIFICATION_ID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

private fun String.substringBeforeAny(vararg delimiters: Char): String {
    val index = indexOfAny(delimiters)
    return if (index < 0) this else substring(0, index)
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

    suspend fun availability(): MobileDeviceCredentialAvailability =
        if (restore() == null) {
            MobileDeviceCredentialAvailability.Absent
        } else {
            MobileDeviceCredentialAvailability.Available
        }
}

enum class MobileDeviceCredentialAvailability {
    Absent,
    Available,
    Unreadable,
}

class InMemoryMobileDeviceCredentialVault(
    initialCredential: MobileDeviceCredential? = null,
) : MobileDeviceCredentialVault {
    private var credential = initialCredential

    override suspend fun restore(): MobileDeviceCredential? = credential

    override suspend fun save(credential: MobileDeviceCredential) {
        this.credential = credential
    }

    override suspend fun clear() {
        credential = null
    }
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
) {
    override fun toString(): String =
        "PushMessage(messageId=<redacted>,dataKeys=${data.keys.sorted()}," +
            "sentAtEpochMilliseconds=$sentAtEpochMilliseconds,timeToLiveSeconds=$timeToLiveSeconds)"
}

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
