package com.botglobal.mobile.platform.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class AndroidPreferenceNotificationInbox(
    context: Context,
    storageName: String,
) : NotificationInbox {
    private val mutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        storageName.requireStorageIdentifier(),
        Context.MODE_PRIVATE,
    )
    private val mutableNotifications = MutableStateFlow(readAll())
    override val notifications: StateFlow<List<SemanticNotification>> =
        mutableNotifications.asStateFlow()

    override suspend fun list(): List<SemanticNotification> = mutex.withLock {
        mutableNotifications.value
    }

    override suspend fun store(notification: SemanticNotification): NotificationStoreOutcome =
        mutex.withLock {
            val current = mutableNotifications.value
            if (current.any { it.id == notification.id }) {
                NotificationStoreOutcome.Duplicate
            } else {
                persist(listOf(notification.copy(isRead = false)) + current)
                NotificationStoreOutcome.Added
            }
        }

    override suspend fun markRead(id: String) {
        mutex.withLock {
            persist(mutableNotifications.value.map {
                if (it.id == id) it.copy(isRead = true) else it
            })
        }
    }

    override suspend fun markAllRead() {
        mutex.withLock {
            persist(mutableNotifications.value.map { it.copy(isRead = true) })
        }
    }

    override suspend fun unreadCount(): Int = mutex.withLock {
        mutableNotifications.value.count { !it.isRead }
    }

    private fun readAll(): List<SemanticNotification> {
        val raw = preferences.getString(STORE_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    array.getJSONObject(index).toNotification()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(notifications: List<SemanticNotification>) {
        val array = JSONArray()
        notifications.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(STORE_KEY, array.toString()).commit()) {
            "Unable to persist the notification inbox."
        }
        mutableNotifications.value = notifications
    }

    private fun SemanticNotification.toJson() = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("titleAr", titleAr)
        .put("titleEn", titleEn)
        .put("bodyAr", bodyAr)
        .put("bodyEn", bodyEn)
        .put("createdAtUtc", createdAtUtc)
        .put("priority", priority.name)
        .put("isRead", isRead)
        .apply {
            soundKey?.let { put("soundKey", it) }
            when (val target = destination) {
                is SemanticNotificationDestination.Internal -> {
                    put("destinationKind", "internal")
                    put("destinationValue", target.route)
                }
                is SemanticNotificationDestination.ExternalHttps -> {
                    put("destinationKind", "external")
                    put("destinationValue", target.url)
                }
                null -> Unit
            }
        }

    private fun JSONObject.toNotification(): SemanticNotification? {
        val id = sequenceOf(optString("id"), optString("notificationId"))
            .firstOrNull(::isSemanticNotificationId) ?: return null
        val destination = when (optString("destinationKind")) {
            "internal" -> optString("destinationValue")
                .takeIf(String::isNotBlank)
                ?.let(SemanticNotificationDestination::Internal)
            "external" -> optString("destinationValue")
                .takeIf(String::isNotBlank)
                ?.let(SemanticNotificationDestination::ExternalHttps)
            else -> optString("actionUrl")
                .takeIf(String::isNotBlank)
                ?.let(SemanticNotificationDestination::ExternalHttps)
        }
        return SemanticNotification(
            id = id,
            type = optString("type", "general"),
            titleAr = optString("titleAr"),
            titleEn = optString("titleEn"),
            bodyAr = optString("bodyAr"),
            bodyEn = optString("bodyEn"),
            createdAtUtc = optString("createdAtUtc"),
            priority = when {
                optInt("priority", 1) >= 2 -> SemanticNotificationPriority.High
                else -> runCatching {
                    SemanticNotificationPriority.valueOf(optString("priority"))
                }.getOrDefault(SemanticNotificationPriority.Normal)
            },
            destination = destination,
            soundKey = optString("soundKey").takeIf(String::isNotBlank),
            isRead = optBoolean("isRead", false),
        )
    }

    private fun String.requireStorageIdentifier(): String = trim().takeIf { value ->
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "._-" }
    } ?: error("A valid notification storage name is required.")

    private companion object {
        const val STORE_KEY = "notifications"
    }
}
