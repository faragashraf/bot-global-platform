package com.ashraffarag.sentricam.motion.presentation

import com.ashraffarag.sentricam.settings.domain.SettingsDestination
import com.ashraffarag.sentricam.settings.domain.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionActionBindingTest {
    @Test
    fun singleTapTogglesMotionWithoutOpeningSettings() {
        val fixture = Fixture()
        fixture.primary.click()

        assertTrue(fixture.motionEnabled)
        assertEquals(1, fixture.toggleCount)
        assertEquals(0, fixture.settingsOpenCount)
    }

    @Test
    fun longPressIsConsumedOpensMotionSettingsAndDoesNotToggle() {
        val fixture = Fixture(initialMotionEnabled = true)
        val consumed = fixture.primary.longClick()
        if (!consumed) fixture.primary.click()

        assertTrue(consumed)
        assertTrue(fixture.motionEnabled)
        assertEquals(0, fixture.toggleCount)
        assertEquals(1, fixture.settingsOpenCount)
        assertEquals(SettingsSection.MOTION_DETECTION, fixture.openedDestination?.section)
    }

    @Test
    fun visibleFallbackOpensMotionSettingsWithoutChangingMotionState() {
        val fixture = Fixture(initialMotionEnabled = false)
        fixture.fallback.click()

        assertFalse(fixture.motionEnabled)
        assertEquals(0, fixture.toggleCount)
        assertEquals(SettingsSection.MOTION_DETECTION, fixture.openedDestination?.section)
    }

    @Test
    fun accessibilityActionOpensSettingsAndIsConsumed() {
        val fixture = Fixture()
        assertTrue(fixture.primary.accessibilityOpenSettings())
        assertEquals(1, fixture.settingsOpenCount)
        assertEquals(0, fixture.toggleCount)
    }

    @Test
    fun api23TooltipListenerIsReplacedByApplicationBinding() {
        var tooltipLongClicks = 0
        val primary = FakePrimaryTarget().apply {
            longAction = {
                tooltipLongClicks++
                true
            }
        }
        var settingsOpenCount = 0
        val binding = MotionActionBindingController(
            MotionActionController({}, { settingsOpenCount++ }),
        )

        // Mirrors API 23: TooltipCompat binds first, then the application action is rebound.
        binding.bindPrimary(primary)
        assertTrue(primary.longClick())
        assertEquals(0, tooltipLongClicks)
        assertEquals(1, settingsOpenCount)
        assertTrue(primary.isLongClickable)
    }

    @Test
    fun recreatedActionViewReceivesBothListeners() {
        var toggles = 0
        var opens = 0
        val binding = MotionActionBindingController(
            MotionActionController({ toggles++ }, { opens++ }),
        )
        val first = FakePrimaryTarget()
        val recreated = FakePrimaryTarget()

        binding.bindPrimary(first)
        binding.bindPrimary(recreated)
        recreated.click()
        assertTrue(recreated.longClick())

        assertEquals(1, toggles)
        assertEquals(1, opens)
        assertNotNull(recreated.capturedClick)
        assertNotNull(recreated.longAction)
    }

    @Test
    fun toolbarVisibilityChangesDoNotRemoveBindings() {
        val fixture = Fixture()
        fixture.primary.visible = false
        fixture.primary.visible = true
        fixture.primary.click()
        assertTrue(fixture.primary.longClick())

        assertEquals(1, fixture.toggleCount)
        assertEquals(1, fixture.settingsOpenCount)
    }

    @Test
    fun touchTargetPolicyMeetsAccessibilityMinimum() {
        assertTrue(MotionActionUiPolicy.MINIMUM_TOUCH_TARGET_DP >= 48)
    }

    @Test
    fun oneTimeHintIsConsumedOnlyOnceAndPersistsInStore() {
        val store = FakeHintStore()
        val firstOwner = MotionSettingsHintPolicy(store)
        assertTrue(firstOwner.consumeHint())
        assertFalse(firstOwner.consumeHint())

        val recreatedOwner = MotionSettingsHintPolicy(store)
        assertFalse(recreatedOwner.consumeHint())
    }

    private class Fixture(initialMotionEnabled: Boolean = false) {
        var motionEnabled = initialMotionEnabled
        var toggleCount = 0
        var settingsOpenCount = 0
        var openedDestination: SettingsDestination? = null
        val primary = FakePrimaryTarget()
        val fallback = FakeFallbackTarget()

        init {
            val controller = MotionActionController(
                toggleMonitoring = {
                    toggleCount++
                    motionEnabled = !motionEnabled
                },
                openSettings = {
                    settingsOpenCount++
                    openedDestination = SettingsDestination.motion()
                },
            )
            MotionActionBindingController(controller).apply {
                bindPrimary(primary)
                bindFallback(fallback)
            }
        }
    }

    private class FakePrimaryTarget : MotionPrimaryActionTarget {
        override var isLongClickable = false
        var visible = true
        var capturedClick: (() -> Unit)? = null
        var longAction: (() -> Boolean)? = null
        var accessibilityAction: (() -> Boolean)? = null

        override fun setClickAction(action: () -> Unit) { capturedClick = action }
        override fun setLongClickAction(action: () -> Boolean) { longAction = action }
        override fun setOpenSettingsAccessibilityAction(action: () -> Boolean) {
            accessibilityAction = action
        }

        fun click() = requireNotNull(capturedClick).invoke()
        fun longClick() = requireNotNull(longAction).invoke()
        fun accessibilityOpenSettings() = requireNotNull(accessibilityAction).invoke()
    }

    private class FakeFallbackTarget : MotionFallbackActionTarget {
        private var capturedClick: (() -> Unit)? = null
        override fun setClickAction(action: () -> Unit) { capturedClick = action }
        fun click() = requireNotNull(capturedClick).invoke()
    }

    private class FakeHintStore : MotionSettingsHintStore {
        override var hasShownMotionSettingsHint = false
    }
}
