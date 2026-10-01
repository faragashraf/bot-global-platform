package com.ashraffarag.sentricam.communication.signalr.android

import android.content.Context
import com.ashraffarag.sentricam.communication.signalr.SignalRClock
import com.ashraffarag.sentricam.communication.signalr.SnapshotVersionProvider

class SharedPreferencesSnapshotVersionProvider(
    context: Context,
    private val clock: SignalRClock,
) : SnapshotVersionProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun next(): Long {
        val previous = preferences.getLong(KEY_LAST_VERSION, 0L)
        val next = maxOf(previous + 1L, clock.nowMillis())
        check(preferences.edit().putLong(KEY_LAST_VERSION, next).commit()) {
            "SignalR snapshot version storage failed"
        }
        return next
    }

    private companion object {
        const val PREFERENCES = "signalr_connection_metadata"
        const val KEY_LAST_VERSION = "last_snapshot_version"
    }
}
