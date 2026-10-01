package com.ashraffarag.sentricam.device.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun firstStartupGeneratesAndPersistsStableUuid() {
        val store = MemoryStore()
        var now = 100L
        val repository = repository(store, { now }, ids = mutableListOf(UUID_ONE, UUID_TWO))

        val first = repository.start()
        now = 200L
        val second = repository(store, { now }, ids = mutableListOf(UUID_TWO)).start()

        assertEquals(UUID_ONE, first.deviceId)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(100L, second.installedAtMillis)
        assertEquals(200L, second.lastStartupAtMillis)
        assertEquals(200L, store.value?.lastStartupAtMillis)
    }

    @Test
    fun invalidLegacyIdIsReplacedWhileLegacyNameSurvives() {
        val store = MemoryStore(StoredDeviceIdentity("legacy-non-uuid", "Hallway", null))
        val identity = repository(store, { 55L }, mutableListOf(UUID_ONE)).start()

        assertEquals(UUID_ONE, identity.deviceId)
        assertEquals("Hallway", identity.friendlyName)
        assertEquals(55L, identity.installedAtMillis)
        assertEquals(DefaultDeviceIdentityRepository.CURRENT_SCHEMA_VERSION, store.value?.schemaVersion)
    }

    @Test
    fun renameIsTrimmedPersistedAndDoesNotChangeIdentity() {
        val store = MemoryStore()
        val repository = repository(store, { 1L }, mutableListOf(UUID_ONE))
        val original = repository.start()
        val renamed = repository.rename("  Front Door  ")

        assertEquals("Front Door", renamed.friendlyName)
        assertEquals(original.deviceId, renamed.deviceId)
        assertEquals("Front Door", store.value?.friendlyName)
        assertNotEquals(original.friendlyName, renamed.friendlyName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankFriendlyNameIsRejected() {
        val repository = repository(MemoryStore(), { 1L }, mutableListOf(UUID_ONE))
        repository.start()
        repository.rename("   ")
    }

    private fun repository(
        store: MemoryStore,
        now: () -> Long,
        ids: MutableList<String>,
    ) = DefaultDeviceIdentityRepository(
        store = store,
        metadata = metadata(),
        clock = DeviceClock(now),
        idGenerator = DeviceIdGenerator { ids.removeFirst() },
    )

    private fun metadata() = DevicePlatformMetadata(
        platform = DevicePlatform.ANDROID,
        defaultFriendlyName = "Pixel",
        appVersion = "1.0",
        deviceModel = "Google Pixel",
        androidVersion = "16",
        buildFingerprint = "fingerprint",
    )

    private class MemoryStore(initial: StoredDeviceIdentity? = null) : DeviceIdentityStore {
        var value = initial
        override fun load() = value
        override fun save(identity: StoredDeviceIdentity) {
            value = identity
        }
    }

    private companion object {
        const val UUID_ONE = "1cf83aa2-bd35-4299-8fe3-55d4fe75d6b8"
        const val UUID_TWO = "f096beef-282a-425c-934e-f24472520607"
    }
}
