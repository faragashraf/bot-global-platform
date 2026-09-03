package com.enpo.connect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.botglobal.mobile.platform.notifications.SemanticNotification
import com.botglobal.mobile.platform.notifications.SemanticNotificationDestination
import com.botglobal.mobile.platform.notifications.SemanticNotificationPriority

@Composable
fun EnpoNotificationsScreen(
    strings: EnpoStrings,
    isArabic: Boolean,
    notifications: List<SemanticNotification>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenAction: (SemanticNotificationDestination) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    val selected = notifications.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected?.id) {
        selected?.takeIf { !it.isRead }?.let { onMarkRead(it.id) }
    }

    if (selected == null) {
        EnpoPairedScreen(
            strings = strings,
            selectedTab = EnpoPairedTab.Notifications,
            unreadNotificationCount = notifications.count { !it.isRead },
            onSettings = onSettings,
            onNotifications = onNotifications,
            onProfile = onProfile,
        ) {
            Text(
                strings.notifications,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            NotificationList(
                strings = strings,
                isArabic = isArabic,
                notifications = notifications,
                onSelect = onSelect,
                onMarkAllRead = onMarkAllRead,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCloseDetail) {
                Text(strings.back)
            }
            Text(
                strings.notificationDetails,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(18.dp))

        NotificationDetail(
            strings = strings,
            isArabic = isArabic,
            notification = selected,
            onOpenAction = onOpenAction,
        )
    }
}

@Composable
private fun NotificationList(
    strings: EnpoStrings,
    isArabic: Boolean,
    notifications: List<SemanticNotification>,
    onSelect: (String) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    Text(
        strings.notificationInboxSubtitle,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .66f),
    )
    if (notifications.any { !it.isRead }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onMarkAllRead) { Text(strings.markAllRead) }
        }
    } else {
        Spacer(Modifier.height(18.dp))
    }
    if (notifications.isEmpty()) {
        Text(strings.noNotifications, style = MaterialTheme.typography.bodyLarge)
        return
    }
    notifications.forEach { notification ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            onClick = { onSelect(notification.id) },
            shape = RoundedCornerShape(8.dp),
            color = if (notification.isRead) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = .08f)
            },
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notification.localizedTitle(isArabic),
                        modifier = Modifier.weight(1f),
                        fontWeight = if (notification.isRead) FontWeight.Medium else FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (notification.isRead) strings.read else strings.unread,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    notification.localizedBody(isArabic),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                    maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    notification.createdAtUtc,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NotificationDetail(
    strings: EnpoStrings,
    isArabic: Boolean,
    notification: SemanticNotification,
    onOpenAction: (SemanticNotificationDestination) -> Unit,
) {
    Text(
        notification.localizedTitle(isArabic),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        if (notification.priority == SemanticNotificationPriority.High) {
            strings.highPriority
        } else {
            strings.normalPriority
        },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        notification.createdAtUtc,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .56f),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(20.dp))
    Text(notification.localizedBody(isArabic), style = MaterialTheme.typography.bodyLarge)
    val action = notification.destination as? SemanticNotificationDestination.ExternalHttps
    if (action != null) {
        Spacer(Modifier.height(26.dp))
        Button(onClick = { onOpenAction(action) }, modifier = Modifier.fillMaxWidth()) {
            Text(strings.openAction)
        }
    }
}

private fun SemanticNotification.localizedTitle(isArabic: Boolean): String =
    if (isArabic) titleAr.ifBlank { titleEn } else titleEn.ifBlank { titleAr }

private fun SemanticNotification.localizedBody(isArabic: Boolean): String =
    if (isArabic) bodyAr.ifBlank { bodyEn } else bodyEn.ifBlank { bodyAr }
