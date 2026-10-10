package com.botglobal.mobile.platform.presence.firebase

import com.botglobal.mobile.platform.presence.PresenceAccount
import com.botglobal.mobile.platform.presence.PresenceLease
import java.net.URI

data class FirebasePresenceConfiguration(
    val enabled: Boolean = false,
    val applicationKey: String = "",
    val appName: String = "nqrb-presence",
    val projectId: String = "",
    val databaseNamespace: String = "",
    val databaseUrl: String = "",
    val allowedDatabaseHost: String = "",
    val apiKey: String = "",
    val applicationId: String = "",
) {
    fun requireValid() {
        if (!enabled) return
        require(applicationKey.isNotBlank() && applicationKey.length <= 64 &&
            applicationKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Presence application key is invalid."
        }
        require(appName.isNotBlank() && appName != "[DEFAULT]") { "Presence requires a distinct named Firebase app." }
        require(projectId.isNotBlank() && apiKey.isNotBlank() && applicationId.isNotBlank()) {
            "Presence Firebase application identity is incomplete."
        }
        val uri = runCatching { URI(databaseUrl) }
            .getOrElse { throw IllegalArgumentException("Presence database origin is not approved.", it) }
        val host = uri.host?.lowercase().orEmpty()
        val namespaceMatchesProject = databaseNamespace == projectId || databaseNamespace == "$projectId-default-rtdb"
        val approvedHost = host == "$databaseNamespace.firebaseio.com" ||
            (host.endsWith(".firebasedatabase.app") && host.startsWith("$databaseNamespace.") &&
                host.removePrefix("$databaseNamespace.").removeSuffix(".firebasedatabase.app")
                    .all { it.isLetterOrDigit() || it == '-' })
        require(namespaceMatchesProject && uri.scheme == "https" && uri.port == -1 && host != "localhost" &&
            host != "127.0.0.1" && uri.userInfo == null && uri.path in setOf("", "/") &&
            uri.query == null && uri.fragment == null && host == allowedDatabaseHost.lowercase() && approvedHost) {
            "Presence database origin is not approved."
        }
    }
}

interface FirebasePresenceLeaseBackend {
    suspend fun bootstrap(account: PresenceAccount): PresenceLease
    suspend fun renew(account: PresenceAccount, leaseId: String): PresenceLease
    suspend fun invalidate(account: PresenceAccount, leaseId: String)
}

interface FirebasePresenceConnection {
    suspend fun heartbeat(generation: Long)
    suspend fun resume(generation: Long)
    suspend fun suspend(generation: Long)
    suspend fun close(generation: Long)
}

fun interface FirebasePresenceDriverFactory {
    suspend fun connect(
        lease: PresenceLease,
        generation: Long,
        event: (FirebasePresenceDriverEvent) -> Unit,
    ): FirebasePresenceConnection
}

sealed interface FirebasePresenceDriverEvent {
    data class Connected(val observedAtEpochMillis: Long) : FirebasePresenceDriverEvent
    data class Unknown(val reason: String) : FirebasePresenceDriverEvent
    data class Failed(val reason: String) : FirebasePresenceDriverEvent
}
