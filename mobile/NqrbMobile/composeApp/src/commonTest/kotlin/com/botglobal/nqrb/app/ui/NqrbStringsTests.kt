package com.botglobal.nqrb.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NqrbStringsTests {
    @Test
    fun englishAndArabicExposeApprovedIdentityAndNewFlows() {
        val arabic = nqrbStrings("ar")
        val english = nqrbStrings("en")

        assertEquals("NQRB", arabic.productName)
        assertEquals("نقرب", arabic.productNameArabic)
        assertEquals("NQRB", english.productName)
        listOf(arabic, english).forEach { strings ->
            assertTrue(strings.continueWithGoogle.isNotBlank())
            assertTrue(strings.contactsOnboardingTitle.isNotBlank())
            assertTrue(strings.contactsStayLocal.isNotBlank())
            assertTrue(strings.allowContacts.isNotBlank())
            assertTrue(strings.notNow.isNotBlank())
            assertTrue(strings.logout.isNotBlank())
            assertTrue(strings.microphoneTitle.isNotBlank())
            assertTrue(strings.startCall.isNotBlank())
            assertTrue(strings.endCall.isNotBlank())
        }
    }
}
