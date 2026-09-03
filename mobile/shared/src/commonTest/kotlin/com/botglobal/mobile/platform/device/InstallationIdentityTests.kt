package com.botglobal.mobile.platform.device

import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class InstallationIdentityTests {
    @Test
    fun existingInstallationIdIsReturnedUnchangedWithoutGeneration() = runTest {
        val existing = InstallationId("existing-installation")
        val store = RecordingInstallationIdStore(existing)
        var generations = 0
        val identity = InstallationIdentity(store) {
            generations += 1
            InstallationId("generated-installation")
        }

        assertEquals(existing, identity.getOrCreate())
        assertEquals(0, generations)
        assertEquals(0, store.writes)
    }

    @Test
    fun absentInstallationIdIsGeneratedAndPersistedOnlyOnce() = runTest {
        val store = RecordingInstallationIdStore()
        var generations = 0
        val identity = InstallationIdentity(store) {
            generations += 1
            InstallationId("generated-$generations")
        }

        assertEquals(InstallationId("generated-1"), identity.getOrCreate())
        assertEquals(InstallationId("generated-1"), identity.getOrCreate())
        assertEquals(1, generations)
        assertEquals(1, store.writes)
    }

    @Test
    fun preferenceStoreAdapterPreservesTheConfiguredKeyAndRedactsDiagnostics() {
        val preferences = InMemoryPreferenceStore(mapOf("legacy_key" to "legacy-installation"))
        val store = PreferenceInstallationIdStore(preferences, "legacy_key")
        val restored = store.read()

        assertEquals(InstallationId("legacy-installation"), restored)
        assertEquals("<redacted>", restored.toString())
        assertFalse("legacy-installation" in restored.toString())
    }

    private class RecordingInstallationIdStore(
        private var value: InstallationId? = null,
    ) : InstallationIdStore {
        var writes = 0

        override fun read(): InstallationId? = value

        override fun write(value: InstallationId) {
            writes += 1
            this.value = value
        }
    }
}
