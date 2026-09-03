package com.botglobal.mobile.platform.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferenceStoreTests {
    @Test
    fun inMemoryStoreHasTheSameContractAsPlatformStores() {
        val store = InMemoryPreferenceStore()

        assertNull(store.string("language"))
        store.putString("language", "ar")

        assertEquals("ar", store.string("language"))
    }
}
