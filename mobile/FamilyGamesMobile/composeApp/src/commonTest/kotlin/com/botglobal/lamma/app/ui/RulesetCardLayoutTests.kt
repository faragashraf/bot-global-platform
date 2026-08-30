package com.botglobal.lamma.app.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesetCardLayoutTests {
    @Test
    fun narrow_phone_reflows_status_below_flexible_content() {
        assertEquals(RulesetCardStatusPlacement.Below, rulesetCardStatusPlacement(288.dp))
    }

    @Test
    fun normal_phone_with_sufficient_content_width_keeps_bounded_status_inline() {
        assertEquals(RulesetCardStatusPlacement.Inline, rulesetCardStatusPlacement(400.dp))
    }

    @Test
    fun breakpoint_prefers_inline_only_when_the_full_minimum_width_is_available() {
        assertEquals(RulesetCardStatusPlacement.Below, rulesetCardStatusPlacement(359.dp))
        assertEquals(RulesetCardStatusPlacement.Inline, rulesetCardStatusPlacement(360.dp))
    }
}
