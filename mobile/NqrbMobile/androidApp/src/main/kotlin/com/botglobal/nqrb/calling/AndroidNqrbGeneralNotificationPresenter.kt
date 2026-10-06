package com.botglobal.nqrb.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.botglobal.mobile.platform.notifications.NotificationInbox
import com.botglobal.mobile.platform.notifications.NotificationStoreOutcome
import com.botglobal.mobile.platform.notifications.SemanticNotification
import com.botglobal.mobile.platform.notifications.SemanticNotificationPriority
import com.botglobal.nqrb.MainActivity
import com.botglobal.nqrb.R
import java.util.UUID
import kotlin.time.Clock

internal class AndroidNqrbGeneralNotificationPresenter(
    private val context: Context,
    private val inbox: NotificationInbox,
) : NqrbGeneralPushNotificationSink {
    override suspend fun show(messageId: String?, title: String, body: String) {
        val notification = semanticNotification(messageId, title, body)
        val outcome = inbox.store(notification)
        if (outcome == NotificationStoreOutcome.Duplicate) return
        val unreadCount = inbox.unreadCount()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createNotificationChannel(notificationManager)
        if (!notificationManager.areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                notificationManager.getNotificationChannel(ChannelId)?.importance == NotificationManager.IMPORTANCE_NONE)
        ) {
            return
        }

        notificationManager.notify(
            notificationId(notification.id),
            notification(title, body, unreadCount),
        )
    }

    private fun notification(title: String, body: String, unreadCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            30,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return notificationBuilder()
            .setSmallIcon(R.drawable.ic_nqrb_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setNumber(unreadCount.coerceAtLeast(1))
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ChannelId)
        } else {
            Notification.Builder(context)
        }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    context.getString(R.string.general_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.general_notification_channel_description)
                    setShowBadge(true)
                },
            )
        }
    }

    private fun semanticNotification(messageId: String?, title: String, body: String): SemanticNotification =
        SemanticNotification(
            id = semanticNotificationId(messageId, title, body),
            type = "general",
            titleAr = title,
            titleEn = title,
            bodyAr = body,
            bodyEn = body,
            createdAtUtc = Clock.System.now().toString(),
            priority = SemanticNotificationPriority.Normal,
            destination = null,
            soundKey = null,
            isRead = false,
        )

    private fun semanticNotificationId(messageId: String?, title: String, body: String): String {
        val key = messageId?.takeIf(String::isNotBlank) ?: "$title\n$body"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun notificationId(id: String): Int {
        val hash = id.hashCode().toLong().let { if (it < 0) -it else it }
        return NotificationIdBase + (hash % NotificationIdRange).toInt()
    }

    private companion object {
        const val ChannelId = "nqrb_general_notifications"
        const val NotificationIdBase = 2200
        const val NotificationIdRange = 100_000
    }
}
