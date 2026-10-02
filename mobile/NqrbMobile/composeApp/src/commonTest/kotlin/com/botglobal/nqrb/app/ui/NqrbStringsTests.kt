package com.botglobal.nqrb.app.ui

import com.botglobal.nqrb.app.config.NqrbPublicSite
import com.botglobal.nqrb.app.data.NqrbContactInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NqrbStringsTests {
    @Test
    fun englishAndArabicExposeApprovedIdentityAndNewFlows() {
        val arabic = nqrbStrings("ar")
        val english = nqrbStrings("en")

        assertEquals("Nqrb", arabic.productName)
        assertEquals("نقرب", arabic.productNameArabic)
        assertEquals("Nqrb", english.productName)
        listOf(arabic, english).forEach { strings ->
            assertTrue(strings.continueWithGoogle.isNotBlank())
            assertTrue(strings.restoringTitle.isNotBlank())
            assertTrue(strings.restoringBody.isNotBlank())
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
            assertTrue(strings.savedContactsTitle.isNotBlank())
            assertTrue(strings.savedContactsBody.isNotBlank())
            assertTrue(strings.savedContactsEmpty.isNotBlank())
            assertTrue(strings.searchNqrbUsers.isNotBlank())
            assertTrue(strings.searchPlaceholder.isNotBlank())
            assertTrue(strings.addContact.isNotBlank())
            assertTrue(strings.removeContact.isNotBlank())
            assertTrue(strings.contactSearchTooShort.isNotBlank())
            assertTrue(strings.inviteContactsTitle.isNotBlank())
            assertTrue(strings.createInvite.isNotBlank())
            assertTrue(strings.acceptInvite.isNotBlank())
            assertTrue(strings.invitePreviewBody.contains("%s"))
            assertTrue(strings.peopleBody.isNotBlank())
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

    @Test
    fun shareTextIncludesCodeAsFallbackForAppsThatDoNotLinkCustomSchemes() {
        val invite = NqrbContactInvite(
            code = "NQ-1234-5678",
            shareLink = "nqrb://invite/NQ-1234-5678",
            expiresAtUtc = "2099-01-01T00:00:00Z",
        )
        listOf(nqrbStrings("ar"), nqrbStrings("en")).forEach { strings ->
            val text = inviteShareMessage(strings, invite)
            assertTrue(text.contains(invite.code))
            assertTrue(text.contains(invite.shareLink))
            assertTrue(!text.contains("%s"))
        }
    }
}
