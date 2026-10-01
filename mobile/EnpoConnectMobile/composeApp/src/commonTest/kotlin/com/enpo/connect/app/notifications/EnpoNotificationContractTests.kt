package com.enpo.connect.app.notifications

import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.SemanticNotificationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EnpoNotificationContractTests {
    @Test
    fun firebaseIdentityIsEnpoOnly() {
        assertEquals("com.enpo.connect", EnpoNotificationContract.PackageName)
        assertEquals("fcm", EnpoNotificationContract.FirebaseProvider)
        assertEquals(
            "EnpoConnectMobile/androidApp/google-services.json",
            EnpoNotificationContract.FirebaseConfigurationFile,
        )
    }

    @Test
    fun allSixLegacySoundsHaveStableChannelMappings() {
        assertEquals(
            listOf("classic", "soft", "alert", "chime", "bell", "ping"),
            EnpoNotificationContract.sounds.map { it.storageKey },
        )
        EnpoNotificationContract.sounds.forEach { sound ->
            assertEquals(
                "enpo_connect_notifications_normal_${sound.storageKey}",
                EnpoNotificationContract.channelId(false, sound.storageKey),
            )
            assertEquals(
                "enpo_connect_notifications_high_${sound.storageKey}",
                EnpoNotificationContract.channelId(true, sound.storageKey),
            )
        }
    }

    @Test
    fun validEnpoHttpsActionIsAcceptedAndUnsafeActionsAreDropped() {
        val valid = parse("https://botglobalservice.com/backend/connect/action?id=1")
        val http = parse("http://botglobalservice.com/backend/connect/action")
        val missingPrefix = parse("https://botglobalservice.com/connect/action")
        val deceptive = parse("https://botglobalservice.com.evil.invalid/backend/action")
        val traversal = parse("https://botglobalservice.com/backend/../connect/action")

        assertIs<SemanticNotificationDestination.ExternalHttps>(valid.destination)
        assertNull(http.destination)
        assertNull(missingPrefix.destination)
        assertNull(deceptive.destination)
        assertNull(traversal.destination)
        assertEquals(
            "https://botglobalservice.com/backend/connect/action?id=1",
            EnpoNotificationContract.validatedActionUrl("https://botglobalservice.com/backend/connect/action?id=1"),
        )
        assertNull(EnpoNotificationContract.validatedActionUrl("https://bgapi.challengershoes.com/connect/action?id=1"))
    }

    private fun parse(actionUrl: String) = EnpoNotificationContract.parser().parse(
        PushMessage(
            messageId = null,
            data = mapOf(
                "notificationId" to "01234567-89ab-cdef-0123-456789abcdef",
                "actionUrl" to actionUrl,
            ),
            sentAtEpochMilliseconds = 0,
            timeToLiveSeconds = 60,
        ),
    )!!
}
