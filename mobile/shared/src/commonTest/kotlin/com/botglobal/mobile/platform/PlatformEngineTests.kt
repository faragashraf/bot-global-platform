package com.botglobal.mobile.platform

import com.botglobal.mobile.platform.entitlements.EntitlementEngine
import com.botglobal.mobile.platform.entitlements.EntitlementGrant
import com.botglobal.mobile.platform.entitlements.EntitlementKey
import com.botglobal.mobile.platform.realtime.StaleEventGuard
import com.botglobal.mobile.platform.realtime.VersionedEvent
import com.botglobal.mobile.platform.update.AppVersionPolicy
import com.botglobal.mobile.platform.update.UpdateMode
import com.botglobal.mobile.platform.update.UpdatePolicyEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformEngineTests {
    @Test
    fun required_update_blocks_below_minimum_version() {
        val decision = UpdatePolicyEngine.decide(AppVersionPolicy("1.1.9", "1.3.0", "1.2.0"))
        assertEquals(UpdateMode.Required, decision.mode)
    }

    @Test
    fun optional_update_is_dismissible_above_minimum() {
        val decision = UpdatePolicyEngine.decide(AppVersionPolicy("1.2.0", "1.3.0", "1.2.0"))
        assertEquals(UpdateMode.Optional, decision.mode)
    }

    @Test
    fun entitlement_engine_uses_semantic_keys_without_premium_flag() {
        val engine = EntitlementEngine(listOf(EntitlementGrant("games.xo.extended", true)))
        assertTrue(engine.isAllowed(EntitlementKey.Ruleset("games.xo.extended")))
        assertFalse(engine.isAllowed(EntitlementKey.GameMode("games.voice")))
        assertTrue(engine.isAllowed(null))
    }

    @Test
    fun stale_realtime_event_is_discarded() {
        assertNull(StaleEventGuard.accept(3, VersionedEvent(2, "old")))
        assertEquals("new", StaleEventGuard.accept(3, VersionedEvent(4, "new")))
    }
}
