package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals

class NqrbRingtoneSettingsTests {
    @Test
    fun defaultIsAppToneAndChoicePersistsAcrossControllerCreation() {
        val store = InMemoryPreferenceStore()
        val first = NqrbRingtoneSettings(store)
        assertEquals(NqrbRingtone.Madar, first.selection.value)

        first.select(NqrbRingtone.Gentle)

        assertEquals(NqrbRingtone.Gentle, NqrbRingtoneSettings(store).selection.value)
    }

    @Test
    fun unknownStoredValueFallsBackToAppTone() {
        val store = InMemoryPreferenceStore(mapOf("incoming_call_ringtone" to "removed-tone"))
        assertEquals(NqrbRingtone.Madar, NqrbRingtoneSettings(store).selection.value)
    }

    @Test
    fun selectedPhoneTonePersistsAndCanBeReplacedByAppTone() {
        val store = InMemoryPreferenceStore()
        val first = NqrbRingtoneSettings(store)
        first.selectDeviceTone("content://media/internal/audio/media/42", "Bluebell")

        val restored = NqrbRingtoneSettings(store)
        assertEquals(NqrbRingtone.Device, restored.selection.value)
        assertEquals("content://media/internal/audio/media/42", restored.deviceToneUri)
        assertEquals("Bluebell", restored.deviceToneName.value)

        restored.select(NqrbRingtone.Madar)
        assertEquals(NqrbRingtone.Madar, NqrbRingtoneSettings(store).selection.value)
    }
}
