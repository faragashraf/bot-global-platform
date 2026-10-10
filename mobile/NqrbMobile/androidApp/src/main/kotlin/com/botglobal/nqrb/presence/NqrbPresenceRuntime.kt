package com.botglobal.nqrb.presence

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.presence.DisabledPresenceGateway
import com.botglobal.mobile.platform.presence.PresenceAccount
import com.botglobal.mobile.platform.presence.PresenceController
import com.botglobal.mobile.platform.presence.PresenceLease
import com.botglobal.mobile.platform.presence.PresenceLifecycleState
import com.botglobal.mobile.platform.presence.firebase.AndroidFirebasePresenceProvider
import com.botglobal.mobile.platform.presence.firebase.FirebasePresenceConfiguration
import com.botglobal.mobile.platform.presence.firebase.FirebasePresenceLeaseBackend
import com.botglobal.mobile.platform.presence.firebase.FirebaseSdkPresenceDriverFactory
import com.botglobal.nqrb.app.data.NqrbSessionAvailability
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class NqrbPresenceRuntime(
    private val application: Application,
    client: HttpClient,
    apiBaseUrl: String,
    sessionVault: SessionVault,
    availability: StateFlow<NqrbSessionAvailability>,
    configuration: FirebasePresenceConfiguration,
) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = NqrbPresenceLeaseApi(client, apiBaseUrl, sessionVault)
    private val gateway = if (configuration.enabled) {
        AndroidFirebasePresenceProvider(
            configuration,
            backend,
            FirebaseSdkPresenceDriverFactory(application, configuration, scope),
            scope,
        )
    } else DisabledPresenceGateway
    val controller = PresenceController(scope, gateway)
    @Volatile private var startedActivities = 0
    @Volatile private var latestOnline: NqrbSessionAvailability.Online? = null

    init {
        application.registerActivityLifecycleCallbacks(this)
        scope.launch {
            availability.collect { value ->
                latestOnline = value as? NqrbSessionAvailability.Online
                when (value) {
                    is NqrbSessionAvailability.Online -> if (startedActivities > 0) {
                        controller.bind(
                            PresenceAccount(
                                value.session.identity.applicationKey,
                                value.session.identity.membershipId,
                                value.session.identity.subjectId,
                                credentialGeneration(value.session.accessToken),
                            ),
                        )
                    }
                    is NqrbSessionAvailability.CachedLocal,
                    NqrbSessionAvailability.Unavailable,
                    -> controller.clear()
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        if (startedActivities != 1) return
        scope.launch {
            if (controller.state.value.state == PresenceLifecycleState.Suspended) controller.resume()
            else latestOnline?.let { online ->
                controller.bind(PresenceAccount(
                    online.session.identity.applicationKey,
                    online.session.identity.membershipId,
                    online.session.identity.subjectId,
                    credentialGeneration(online.session.accessToken),
                ))
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations) scope.launch { controller.suspend() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

private class NqrbPresenceLeaseApi(
    client: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
) : FirebasePresenceLeaseBackend {
    private val owners = ConcurrentHashMap<String, MobileSession>()
    private val client = client.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override suspend fun bootstrap(account: PresenceAccount): PresenceLease {
        val session = requireCurrentSession(account)
        owners[account.stableKey] = session
        try {
            val response = client.post(endpoint("/api/mobile/presence/lease")) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
            }
            check(response.status.value in 200..299) { "presence_bootstrap_failed" }
            return response.body<PresenceLeaseDto>().toDomain()
        } catch (error: Throwable) {
            owners.remove(account.stableKey, session)
            throw error
        }
    }

    override suspend fun renew(account: PresenceAccount, leaseId: String): PresenceLease {
        val session = owners[account.stableKey] ?: error("presence_session_unavailable")
        val response = client.post(endpoint("/api/mobile/presence/lease/renew")) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PresenceLeaseRequest(leaseId))
        }
        check(response.status.value in 200..299) { "presence_renewal_failed" }
        return response.body<PresenceLeaseDto>().toDomain()
    }

    override suspend fun invalidate(account: PresenceAccount, leaseId: String) {
        val session = owners.remove(account.stableKey) ?: return
        try {
            client.post(endpoint("/api/mobile/presence/lease/invalidate")) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(PresenceLeaseRequest(leaseId))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort only: the lease is numerically bounded and the caller
            // has already fenced this owner generation locally.
        }
    }

    private suspend fun requireCurrentSession(account: PresenceAccount) =
        sessionVault.restore()?.takeIf {
            it.identity.applicationKey == account.applicationKey &&
                it.identity.membershipId == account.membershipId &&
                it.identity.subjectId == account.subjectId &&
                credentialGeneration(it.accessToken) == account.credentialGeneration
        } ?: error("presence_session_unavailable")

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path
}

@Serializable
internal data class PresenceLeaseRequest(val leaseId: String)

@Serializable
internal data class PresenceLeaseDto(
    val customToken: String,
    val databaseUrl: String,
    val connectionPath: String,
    val leaseId: String,
    val expiresAtUtc: String,
    val heartbeatSeconds: Int,
    val freshnessSeconds: Int,
) {
    fun toDomain() = PresenceLease(
        leaseId,
        customToken,
        databaseUrl,
        connectionPath,
        Instant.parse(expiresAtUtc).toEpochMilliseconds(),
        heartbeatSeconds,
        freshnessSeconds,
    )
}

private fun credentialGeneration(accessToken: String): String =
    MessageDigest.getInstance("SHA-256").digest(accessToken.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
