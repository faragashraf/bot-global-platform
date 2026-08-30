package com.botglobal.nqrb.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NqrbStringsTests {
    @Test
    fun englishAndArabicExposeTheApprovedProductIdentity() {
        val arabic = nqrbStrings("ar")
        val english = nqrbStrings("en")

        assertEquals("NQRB", arabic.productName)
        assertEquals("نقرب", arabic.productNameArabic)
        assertEquals("NQRB", english.productName)
        assertEquals("نقرب", english.productNameArabic)
    }

    @Test
    fun requiredShellCopyExistsInBothLanguages() {
        listOf(nqrbStrings("ar"), nqrbStrings("en")).forEach { strings ->
            assertTrue(strings.home.isNotBlank())
            assertTrue(strings.history.isNotBlank())
            assertTrue(strings.people.isNotBlank())
            assertTrue(strings.profile.isNotBlank())
            assertTrue(strings.settings.isNotBlank())
            assertTrue(strings.createCallLinkTitle.isNotBlank())
            assertTrue(strings.primaryCallTitle.isNotBlank())
        }
    }
}
