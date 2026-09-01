package com.botglobal.nqrb.calling

import android.annotation.SuppressLint
import android.content.Context
import com.botglobal.mobile.platform.calling.FinalCallUsage
import com.botglobal.mobile.platform.calling.PendingCallUsageStore

// A final usage report must be on disk before the call-cleanup path can continue.
@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidPendingCallUsageStore(context: Context) : PendingCallUsageStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "botglobal_nqrb_pending_call_usage",
        Context.MODE_PRIVATE,
    )

    override suspend fun load(): List<FinalCallUsage> = preferences.all.mapNotNull { (callId, raw) ->
        val values = (raw as? String)?.split('|') ?: return@mapNotNull null
        if (values.size != 4) return@mapNotNull null
        FinalCallUsage(callId, values[1].toLongOrNull() ?: return@mapNotNull null,
            values[2].toLongOrNull() ?: return@mapNotNull null, values[3].toLongOrNull() ?: return@mapNotNull null,
            values[0])
    }

    override suspend fun save(usage: FinalCallUsage) {
        check(preferences.edit().putString(usage.callId,
            "${requireNotNull(usage.ownerMembershipId)}|${usage.bytesSent}|${usage.bytesReceived}|${usage.connectedDurationSeconds}").commit()) {
            "Pending call usage could not be persisted."
        }
    }

    override suspend fun remove(callId: String) {
        preferences.edit().remove(callId).commit()
    }
}
