package com.enpo.connect.app.notifications

import com.botglobal.mobile.platform.notifications.HttpsHostAllowlist
import com.botglobal.mobile.platform.notifications.SemanticNotificationDestination
import com.botglobal.mobile.platform.notifications.SemanticPushEnvelopeParser

enum class EnpoNotificationSound(val storageKey: String) {
    Classic("classic"),
    Soft("soft"),
    Alert("alert"),
    Chime("chime"),
    Bell("bell"),
    Ping("ping"),
    ;

    companion object {
        fun fromStorage(value: String?): EnpoNotificationSound =
            entries.firstOrNull { it.storageKey == value?.trim()?.lowercase() } ?: Soft
    }
}

object EnpoNotificationContract {
    const val FirebaseProvider = "fcm"
    const val FirebaseConfigurationFile = "EnpoConnectMobile/androidApp/google-services.json"
    const val PackageName = "com.enpo.connect"
    const val InboxStorageName = "enpo_connect_notifications"
    const val ChannelPrefix = "enpo_connect_notifications"
    const val ApprovedActionHost = "bgapi.challengershoes.com"

    val sounds: List<EnpoNotificationSound> = EnpoNotificationSound.entries

    fun parser() = SemanticPushEnvelopeParser(
        externalLinks = HttpsHostAllowlist(setOf(ApprovedActionHost)),
        internalDestination = { route ->
            route.trim().lowercase()
                .takeIf { it == "notifications" }
                ?.let(SemanticNotificationDestination::Internal)
        },
    )

    fun channelId(highPriority: Boolean, soundKey: String): String =
        "${ChannelPrefix}_${if (highPriority) "high" else "normal"}_$soundKey"
}

fun interface EnpoNotificationPermissionRequester {
    fun requestIfAppropriate()
}

object NoOpNotificationPermissionRequester : EnpoNotificationPermissionRequester {
    override fun requestIfAppropriate() = Unit
}

fun interface EnpoNotificationActionHandler {
    fun open(destination: SemanticNotificationDestination)
}

object NoOpNotificationActionHandler : EnpoNotificationActionHandler {
    override fun open(destination: SemanticNotificationDestination) = Unit
}
