package com.botglobal.nqrb.app.ui

import com.botglobal.nqrb.app.config.NqrbPublicSite
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
            assertTrue(strings.restoringTitle.isNotBlank())
            assertTrue(strings.restoringBody.isNotBlank())
            assertTrue(strings.contactsOnboardingTitle.isNotBlank())
            assertTrue(strings.contactsStayLocal.isNotBlank())
            assertTrue(strings.allowContacts.isNotBlank())
            assertTrue(strings.notNow.isNotBlank())
            assertTrue(strings.logout.isNotBlank())
            assertTrue(strings.accountInformation.isNotBlank())
            assertTrue(strings.accountName.isNotBlank())
            assertTrue(strings.emailAddress.isNotBlank())
            assertTrue(strings.accountIdentityLoading.isNotBlank())
            assertTrue(strings.accountIdentityError.isNotBlank())
            assertTrue(strings.privacyPolicy.isNotBlank())
            assertTrue(strings.accountDeletionHelp.isNotBlank())
            assertTrue(strings.support.isNotBlank())
            assertTrue(strings.deleteAccount.isNotBlank())
            assertTrue(strings.deleteAccountFirstTitle.isNotBlank())
            assertTrue(strings.deleteAccountFinalTitle.isNotBlank())
            assertTrue(strings.deleteAccountFirstBody != strings.deleteAccountFinalBody)
            assertTrue(strings.accountDeletionFailed.isNotBlank())
            assertTrue(strings.microphoneTitle.isNotBlank())
            assertTrue(strings.startCall.isNotBlank())
            assertTrue(strings.callablePeopleTitle.isNotBlank())
            assertTrue(strings.callingDirectoryLoading.isNotBlank())
            assertTrue(strings.callingDirectoryEmpty.isNotBlank())
            assertTrue(strings.callingDirectoryError.isNotBlank())
            assertTrue(strings.refreshCallingDirectory.isNotBlank())
            assertTrue(strings.retry.isNotBlank())
            assertTrue(strings.endCall.isNotBlank())
            assertTrue(strings.audioRoute.isNotBlank())
        }
    }

    @Test
    fun publicPrivacyAndDeletionLinksAreVisibleAndUseTheCentralProductionSite() {
        val links = nqrbPublicLinks(nqrbStrings("ar"))

        assertEquals(3, links.size)
        assertEquals(NqrbPublicSite.PrivacyPolicyUrl, links[0].url)
        assertEquals(NqrbPublicSite.AccountDeletionUrl, links[1].url)
        assertEquals(NqrbPublicSite.SupportUrl, links[2].url)
        assertTrue(links.all { it.label.isNotBlank() })
        assertTrue(links.all { it.url.startsWith("https://") })
        assertTrue(links.none { it.url.contains("localhost") || it.url.contains("10.0.2.2") })
    }
}
