package com.botglobal.familygames.app.ui

import com.botglobal.familygames.app.state.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FamilyGamesLocalizationTests {
    @Test
    fun product_name_is_lamma_in_both_supported_languages() {
        assertEquals("لَمّة", strings(AppLanguage.Arabic).appName)
        assertEquals("Lamma", strings(AppLanguage.English).appName)
    }

    @Test
    fun connection_and_recovery_messages_exist_in_both_languages() {
        val arabic = strings(AppLanguage.Arabic)
        val english = strings(AppLanguage.English)

        assertFalse(arabic.reconnecting.isBlank())
        assertFalse(arabic.recovered.isBlank())
        assertFalse(arabic.actionUnavailableOffline.isBlank())
        assertFalse(english.reconnecting.isBlank())
        assertFalse(english.recovered.isBlank())
        assertFalse(english.actionUnavailableOffline.isBlank())
    }

    @Test
    fun backend_details_and_codes_are_replaced_by_localized_semantic_errors() {
        val codes = listOf(
            "stale_version",
            "duplicate_command",
            "concurrent_move",
            "game_completed",
            "recovery_failed",
            "unexpected_internal_backend_detail",
        )

        for (language in AppLanguage.entries) {
            val localized = strings(language)
            codes.forEach { code -> assertFalse(localized.error(code).contains(code)) }
        }

        assertEquals(strings(AppLanguage.Arabic).recoveryFailed, strings(AppLanguage.Arabic).error("recovery_failed"))
        assertEquals(strings(AppLanguage.English).recoveryFailed, strings(AppLanguage.English).error("recovery_failed"))
    }
}
