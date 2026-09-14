package com.botglobal.nqrb.app.ui

import com.botglobal.nqrb.app.data.NqrbAccountProfile
import com.botglobal.nqrb.app.state.NqrbAccountProfileState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NqrbAccountIdentityPresentationTests {
    @Test
    fun authenticatedNameAndEmailArePresentedWithAccountLabels() {
        val strings = nqrbStrings("ar")

        val presentation = accountIdentityPresentation(
            strings,
            NqrbAccountProfileState.Available(NqrbAccountProfile("Authenticated Name", "user@example.test")),
        )!!

        assertEquals("اسم الحساب", presentation.nameLabel)
        assertEquals("Authenticated Name", presentation.displayName)
        assertEquals("البريد الإلكتروني", presentation.emailLabel)
        assertEquals("user@example.test", presentation.email)
    }

    @Test
    fun missingDisplayNameUsesProfessionalLocalizedFallback() {
        val strings = nqrbStrings("ar")

        val presentation = accountIdentityPresentation(
            strings,
            NqrbAccountProfileState.Available(NqrbAccountProfile("  ", "user@example.test")),
        )!!

        assertEquals(strings.unavailableAccountName, presentation.displayName)
    }

    @Test
    fun unauthenticatedOrFailedStateNeverPresentsStaleIdentity() {
        assertNull(accountIdentityPresentation(nqrbStrings("ar"), NqrbAccountProfileState.Hidden))
        assertNull(accountIdentityPresentation(nqrbStrings("ar"), NqrbAccountProfileState.Failed))
    }

    @Test
    fun presentationContainsNoInternalIdentityOrCredentialFields() {
        val presentation = accountIdentityPresentation(
            nqrbStrings("en"),
            NqrbAccountProfileState.Available(NqrbAccountProfile("Person", "person@example.test")),
        )!!
        val renderedValues = listOf(
            presentation.nameLabel,
            presentation.displayName,
            presentation.emailLabel,
            presentation.email,
        ).joinToString(" ")

        listOf("membershipId", "subjectId", "deviceId", "accessToken", "refreshToken").forEach {
            assertFalse(renderedValues.contains(it, ignoreCase = true))
        }
    }
}
