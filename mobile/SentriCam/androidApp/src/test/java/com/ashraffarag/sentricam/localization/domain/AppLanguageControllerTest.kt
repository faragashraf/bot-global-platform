package com.ashraffarag.sentricam.localization.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageControllerTest {
    @Test
    fun noExplicitPreferenceLeavesTheSystemLocaleUnchanged() {
        val preference = MemoryLanguagePreference()
        val applied = mutableListOf<String>()
        val controller = AppLanguageController(preference, applied::add)

        assertNull(controller.restorePersistedLanguage())
        assertNull(controller.selectedLanguage())
        assertTrue(applied.isEmpty())
    }

    @Test
    fun explicitSelectionPersistsAndRestoresAcrossControllerInstances() {
        val preference = MemoryLanguagePreference()
        val firstProcessApplications = mutableListOf<String>()
        val firstController = AppLanguageController(preference, firstProcessApplications::add)

        assertTrue(firstController.selectLanguage(AppLanguage.ARABIC))
        assertEquals("ar", preference.languageTag)
        assertEquals(listOf("ar"), firstProcessApplications)

        val restartedProcessApplications = mutableListOf<String>()
        val restartedController = AppLanguageController(preference, restartedProcessApplications::add)
        assertEquals(AppLanguage.ARABIC, restartedController.restorePersistedLanguage())
        assertEquals(listOf("ar"), restartedProcessApplications)

        assertTrue(restartedController.selectLanguage(AppLanguage.ENGLISH))
        assertEquals("en", preference.languageTag)
        assertEquals(listOf("ar", "en"), restartedProcessApplications)
    }

    @Test
    fun selectingTheCurrentLanguageDoesNotRequestAnotherRecreation() {
        val preference = MemoryLanguagePreference("en")
        val applied = mutableListOf<String>()
        val controller = AppLanguageController(preference, applied::add)

        assertFalse(controller.selectLanguage(AppLanguage.ENGLISH))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun languageTagsMapByLanguageWithoutDependingOnRegion() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromLanguageTag("ar-EG"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en_US"))
        assertNull(AppLanguage.fromLanguageTag("fr"))
        assertNull(AppLanguage.fromLanguageTag(null))
    }

    private class MemoryLanguagePreference(
        var languageTag: String? = null,
    ) : AppLanguagePreference {
        override fun loadLanguageTag(): String? = languageTag

        override fun saveLanguageTag(languageTag: String) {
            this.languageTag = languageTag
        }
    }
}
