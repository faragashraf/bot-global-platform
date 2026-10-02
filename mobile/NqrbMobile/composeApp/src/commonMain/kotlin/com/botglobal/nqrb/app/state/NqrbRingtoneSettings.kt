package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NqrbRingtone { Madar, Gentle, Classic, Clear, Pulse, Device }

/** Product-local, device-wide call sound preference. It is not account data. */
class NqrbRingtoneSettings(
    private val store: PreferenceStore = InMemoryPreferenceStore(),
) {
    private val mutableSelection = MutableStateFlow(readSelection())
    val selection = mutableSelection.asStateFlow()
    val deviceToneUri: String?
        get() = store.string(DeviceToneKey)?.takeIf(String::isNotBlank)
    private val mutableDeviceToneName = MutableStateFlow(store.string(DeviceToneNameKey)?.takeIf(String::isNotBlank))
    val deviceToneName = mutableDeviceToneName.asStateFlow()

    fun select(ringtone: NqrbRingtone) {
        store.putString(PreferenceKey, ringtone.name)
        mutableSelection.value = ringtone
    }

    fun selectDeviceTone(uri: String, name: String? = null) {
        if (uri.isBlank()) return
        store.putString(DeviceToneKey, uri)
        name?.takeIf(String::isNotBlank)?.let {
            store.putString(DeviceToneNameKey, it)
            mutableDeviceToneName.value = it
        }
        select(NqrbRingtone.Device)
    }

    private fun readSelection(): NqrbRingtone =
        NqrbRingtone.entries.firstOrNull { it.name == store.string(PreferenceKey) }
            ?: NqrbRingtone.Madar

    companion object {
        private const val PreferenceKey = "incoming_call_ringtone"
        private const val DeviceToneKey = "incoming_call_device_ringtone_uri"
        private const val DeviceToneNameKey = "incoming_call_device_ringtone_name"
    }
}
