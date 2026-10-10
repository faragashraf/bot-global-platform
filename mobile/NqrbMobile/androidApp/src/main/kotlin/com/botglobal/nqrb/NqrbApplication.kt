package com.botglobal.nqrb

import android.app.Application
import android.util.Log
import com.botglobal.mobile.platform.identity.AndroidSecureSessionVault
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.notifications.PushRegistrationController
import com.botglobal.mobile.platform.notifications.AndroidPreferenceNotificationInbox
import com.botglobal.mobile.platform.notifications.firebase.AndroidFirebaseMessagingRuntime
import com.botglobal.mobile.platform.notifications.firebase.AndroidPushDeviceInstallation
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessageDeliveryPolicy
import com.botglobal.mobile.platform.notifications.firebase.AndroidSecureMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.firebase.FirebaseMessagingRuntimeOwner
import com.botglobal.nqrb.app.data.NqrbPushRegistrationApi
import com.botglobal.nqrb.app.data.NqrbIdentityApi
import com.botglobal.nqrb.app.data.NqrbCallingDirectoryApi
import com.botglobal.nqrb.app.data.NqrbContactBookApi
import com.botglobal.nqrb.app.data.createNqrbHttpClient
import com.botglobal.nqrb.calling.NqrbCallRuntime
import com.botglobal.nqrb.calling.AndroidNqrbGeneralNotificationPresenter
import com.botglobal.nqrb.calling.NqrbPushMessageHandler
import com.botglobal.nqrb.calling.NqrbChatPushSynchronizer
import com.botglobal.nqrb.calling.AndroidPendingCallUsageStore
import com.botglobal.nqrb.app.data.NqrbCallActivityApi
import com.botglobal.nqrb.app.data.NqrbAccountDeletionApi
import com.botglobal.nqrb.app.state.NqrbLocalAccountDataCleaner
import com.botglobal.mobile.platform.calling.CallActivityController
import com.botglobal.nqrb.calling.NqrbOngoingCallService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.botglobal.mobile.platform.chat.AndroidChatDurableStore
import com.botglobal.mobile.platform.chat.AndroidChatVoicePlayer
import com.botglobal.mobile.platform.chat.AndroidChatVoiceRecorder
import com.botglobal.mobile.platform.chat.AndroidChatVoiceStore
import com.botglobal.mobile.platform.chat.ChatActiveCallGuard
import com.botglobal.mobile.platform.chat.ChatController
import com.botglobal.mobile.platform.chat.ChatCredential
import com.botglobal.mobile.platform.chat.ChatCredentialProvider
import com.botglobal.mobile.platform.chat.ChatMicrophonePermission
import com.botglobal.mobile.platform.chat.KtorChatGateway
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.nqrb.chat.NqrbChatRealtime
import com.botglobal.nqrb.presence.NqrbPresenceRuntime
import com.botglobal.mobile.platform.presence.firebase.FirebasePresenceConfiguration

class NqrbApplication : Application(), FirebaseMessagingRuntimeOwner {
    lateinit var callRuntime: NqrbCallRuntime
        private set
    lateinit var sessionVault: AndroidSecureSessionVault
        private set
    lateinit var identityApi: NqrbIdentityApi
        private set
    lateinit var callingDirectoryApi: NqrbCallingDirectoryApi
        private set
    lateinit var contactBookApi: NqrbContactBookApi
        private set
    lateinit var callActivity: CallActivityController
        private set
    lateinit var accountDeletionApi: NqrbAccountDeletionApi
        private set
    lateinit var localAccountDataCleaner: NqrbLocalAccountDataCleaner
        private set
    lateinit var notificationInbox: AndroidPreferenceNotificationInbox
        private set
    lateinit var chat: ChatController
        private set
    lateinit var chatVoiceRecorder: AndroidChatVoiceRecorder
        private set
    lateinit var chatVoicePlayer: AndroidChatVoicePlayer
        private set
    lateinit var presenceRuntime: NqrbPresenceRuntime
        private set
    override lateinit var firebaseMessagingRuntime: AndroidFirebaseMessagingRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        sessionVault = AndroidSecureSessionVault(this, "nqrb")
        identityApi = NqrbIdentityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault) {
            Log.w("NqrbIdentity", it)
        }
        accountDeletionApi = NqrbAccountDeletionApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        callingDirectoryApi = NqrbCallingDirectoryApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        contactBookApi = NqrbContactBookApi(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            sessionVault,
        )
        presenceRuntime = NqrbPresenceRuntime(
            application = this,
            client = createNqrbHttpClient(),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            availability = identityApi.availability,
            configuration = FirebasePresenceConfiguration(
                enabled = BuildConfig.PRESENCE_ENABLED,
                applicationKey = "nqrb",
                projectId = BuildConfig.PRESENCE_PROJECT_ID,
                databaseNamespace = BuildConfig.PRESENCE_DATABASE_NAMESPACE,
                databaseUrl = BuildConfig.PRESENCE_DATABASE_URL,
                allowedDatabaseHost = BuildConfig.PRESENCE_DATABASE_HOST,
                apiKey = BuildConfig.PRESENCE_API_KEY,
                applicationId = BuildConfig.PRESENCE_APPLICATION_ID,
            ),
        )
        val pendingCallUsageStore = AndroidPendingCallUsageStore(this)
        notificationInbox = AndroidPreferenceNotificationInbox(this, "nqrb_notifications")
        callActivity = CallActivityController(
            NqrbCallActivityApi(createNqrbHttpClient(), BuildConfig.API_BASE_URL, sessionVault),
            pendingCallUsageStore,
        )
        val deviceCredentialVault = AndroidSecureMobileDeviceCredentialVault(this, "nqrb")
        val installation = AndroidPushDeviceInstallation(this, BuildConfig.VERSION_NAME).value
        val pushRegistration = NqrbPushRegistrationApi(
            platformClient = createNqrbHttpClient(),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            deviceCredentialVault = deviceCredentialVault,
            installation = installation,
        )
        callRuntime = NqrbCallRuntime(
            application = this,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sessionVault = sessionVault,
            restoreSession = {
                val stored = (identityApi.availability.value as? com.botglobal.nqrb.app.data.NqrbSessionAvailability.Online)?.session
                when {
                    stored == null -> runCatching { identityApi.restore() != null }.getOrDefault(false)
                    stored.accessExpiresSoon() -> identityApi.restore() != null
                    else -> true
                }
            },
        )
        val chatVoiceStore = AndroidChatVoiceStore(this)
        val callGuard = ChatActiveCallGuard {
            callRuntime.session.state.value.state in setOf(
                CallState.Preparing, CallState.Connecting, CallState.Ringing, CallState.Answering,
                CallState.Active, CallState.Reconnecting, CallState.Ending,
            )
        }
        chatVoiceRecorder = AndroidChatVoiceRecorder(
            this,
            chatVoiceStore,
            callGuard,
            ChatMicrophonePermission {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
        )
        chatVoicePlayer = AndroidChatVoicePlayer(this, chatVoiceStore, callGuard)
        val chatGateway = KtorChatGateway(
            createNqrbHttpClient(),
            BuildConfig.API_BASE_URL,
            ChatCredentialProvider {
                (identityApi.availability.value as? com.botglobal.nqrb.app.data.NqrbSessionAvailability.Online)?.session?.let { session ->
                    ChatCredential("Bearer ${session.accessToken}")
                }
            },
        )
        chat = ChatController(
            gateway = chatGateway,
            durableStore = AndroidChatDurableStore(this),
            voiceStore = chatVoiceStore,
            realtime = NqrbChatRealtime(BuildConfig.API_BASE_URL, sessionVault) {
                (identityApi.availability.value as? com.botglobal.nqrb.app.data.NqrbSessionAvailability.Online)?.session?.accessToken
            },
            installationId = { deviceCredentialVault.restore()?.deviceId },
            authenticatedIdentityKey = { sessionVault.restore()?.identity?.let { "${it.applicationKey}\n${it.membershipId}\n${it.subjectId}" } },
        )
        firebaseMessagingRuntime = AndroidFirebaseMessagingRuntime(
            context = this,
            registrationController = PushRegistrationController(pushRegistration),
            messageHandler = NqrbPushMessageHandler(
                callRuntime,
                AndroidNqrbGeneralNotificationPresenter(this, notificationInbox),
                NqrbChatPushSynchronizer { conversationId -> chat.sync(conversationId) },
            ),
            messageDeliveryPolicy = FirebaseMessageDeliveryPolicy(blockOnMessage = true),
        )
        localAccountDataCleaner = NqrbLocalAccountDataCleaner {
            runCatching { presenceRuntime.controller.clear() }
            runCatching { callRuntime.session.clearForAccountChange() }
            runCatching { sessionVault.clear() }
            runCatching { deviceCredentialVault.clear() }
            runCatching { pendingCallUsageStore.clearAll() }
            runCatching { notificationInbox.clearAll() }
            NqrbOngoingCallService.clearStoredPresentation(this)
        }
    }
}

private fun MobileSession.accessExpiresSoon(): Boolean = runCatching {
    Instant.parse(accessExpiresAtUtc) <= Clock.System.now() + 1.minutes
}.getOrDefault(true)
