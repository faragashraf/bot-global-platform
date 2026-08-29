package com.botglobal.mobile.platform.notifications

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

interface PushRegistration {
    suspend fun register(provider: String, token: String)
    suspend fun unregister()
}

interface ForegroundNotificationRouter {
    suspend fun onNotification(notification: SemanticNotification)
}
