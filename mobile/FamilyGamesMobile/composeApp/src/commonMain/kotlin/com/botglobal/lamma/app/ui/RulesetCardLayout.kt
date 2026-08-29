package com.botglobal.lamma.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class RulesetCardStatusPlacement {
    Inline,
    Below,
}

internal val RulesetCardModeBadgeSize = 58.dp
internal val RulesetCardStatusMaxWidth = 140.dp
internal val RulesetCardInlineStatusMinWidth = 360.dp

/**
 * Keeps the mode details flexible on phone widths instead of allowing a long
 * status label to consume their horizontal space. The width is the card's
 * usable content width after its outer padding, so the policy is independent
 * of device model and applies equally in LTR and RTL layouts.
 */
internal fun rulesetCardStatusPlacement(availableWidth: Dp): RulesetCardStatusPlacement =
    if (availableWidth >= RulesetCardInlineStatusMinWidth) {
        RulesetCardStatusPlacement.Inline
    } else {
        RulesetCardStatusPlacement.Below
    }
