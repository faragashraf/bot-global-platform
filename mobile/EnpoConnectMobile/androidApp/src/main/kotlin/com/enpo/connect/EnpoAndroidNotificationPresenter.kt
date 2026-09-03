package com.enpo.connect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.botglobal.mobile.platform.notifications.NotificationPresentationOutcome
import com.botglobal.mobile.platform.notifications.NotificationPresenter
import com.botglobal.mobile.platform.notifications.SemanticNotification
import com.botglobal.mobile.platform.notifications.SemanticNotificationPriority
import com.botglobal.mobile.platform.preferences.PreferenceStore
import com.enpo.connect.app.notifications.EnpoNotificationContract
import com.enpo.connect.app.notifications.EnpoNotificationSound
import com.enpo.connect.app.state.EnpoLegacyStorageCompatibility

class EnpoAndroidNotificationPresenter(
    private val context: Context,
    private val preferences: PreferenceStore,
) : NotificationPresenter {
    override suspend fun present(
        notification: SemanticNotification,
    ): NotificationPresentationOutcome {
        if (preferences.boolean(
                EnpoLegacyStorageCompatibility.NotificationsEnabledPreferenceKey,
            ) == false
        ) {
            return NotificationPresentationOutcome.Disabled
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationPresentationOutcome.PermissionDenied
        }

        val highPriority = notification.priority == SemanticNotificationPriority.High
        val selectedSound = EnpoNotificationSound.fromStorage(
            notification.soundKey
                ?: preferences.string(EnpoLegacyStorageCompatibility.NotificationSoundPreferenceKey),
        )
        val deviceSoundUri = preferences.string(
            EnpoLegacyStorageCompatibility.DeviceNotificationSoundUriPreferenceKey,
        )?.takeIf(String::isNotBlank)
        val soundUri = deviceSoundUri?.let(Uri::parse) ?: selectedSound.resourceUri(context)
        val soundKey = deviceSoundUri?.hashCode()?.toUInt()?.toString(16)
            ?: selectedSound.storageKey
        val channelId = EnpoNotificationContract.channelId(highPriority, soundKey)
        ensureChannel(channelId, highPriority, soundUri)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.NotificationIdExtra, notification.id)
        }
        val requestCode = notification.id.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = notification.titleAr.ifBlank {
            notification.titleEn.ifBlank { context.getString(R.string.app_name) }
        }
        val body = notification.bodyAr.ifBlank { notification.bodyEn }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val systemNotification = builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    setPriority(
                        if (highPriority) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT,
                    )
                    @Suppress("DEPRECATION")
                    setVibrate(longArrayOf(0, 150))
                    setSound(soundUri)
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(requestCode, systemNotification)
        return NotificationPresentationOutcome.Presented
    }

    private fun ensureChannel(channelId: String, highPriority: Boolean, soundUri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(
            channelId,
            if (highPriority) {
                "${context.getString(R.string.notification_channel_name)} (High priority)"
            } else {
                context.getString(R.string.notification_channel_name)
            },
            if (highPriority) NotificationManager.IMPORTANCE_HIGH
            else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
            setSound(
                soundUri,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun EnpoNotificationSound.resourceUri(context: Context): Uri {
        val resource = when (this) {
            EnpoNotificationSound.Classic -> R.raw.enpo_classic
            EnpoNotificationSound.Soft -> R.raw.enpo_soft
            EnpoNotificationSound.Alert -> R.raw.enpo_alert
            EnpoNotificationSound.Chime -> R.raw.enpo_chime
            EnpoNotificationSound.Bell -> R.raw.enpo_bell
            EnpoNotificationSound.Ping -> R.raw.enpo_ping
        }
        return Uri.parse("android.resource://${context.packageName}/$resource")
    }
}
