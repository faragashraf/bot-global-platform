package com.ashraffarag.sentricam.settings.android

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.capability.presentation.CapabilityAccessKind
import com.ashraffarag.sentricam.capability.presentation.CapabilityAccessUiMapper
import com.ashraffarag.sentricam.databinding.ViewCapabilityFeatureRowBinding

class CapabilityFeatureRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ViewCapabilityFeatureRowBinding.inflate(LayoutInflater.from(context), this, true)

    fun bind(name: String, description: String, access: CapabilityAccess) {
        val ui = CapabilityAccessUiMapper.map(access)
        val status = when (ui.kind) {
            CapabilityAccessKind.AVAILABLE -> R.string.capability_available
            CapabilityAccessKind.LOCKED -> R.string.capability_requires_upgrade
            CapabilityAccessKind.COMING_SOON -> R.string.capability_coming_soon
            CapabilityAccessKind.UNSUPPORTED -> R.string.capability_unsupported
            CapabilityAccessKind.UNAVAILABLE -> R.string.capability_unavailable
        }
        binding.capabilityName.text = name
        binding.capabilityDescription.text = description
        binding.capabilityStatus.setText(status)
        val statusColor = ContextCompat.getColor(
            context,
            when (ui.kind) {
                CapabilityAccessKind.AVAILABLE -> R.color.vision_status_healthy
                CapabilityAccessKind.LOCKED,
                CapabilityAccessKind.COMING_SOON,
                -> R.color.vision_brand_secondary
                CapabilityAccessKind.UNSUPPORTED,
                CapabilityAccessKind.UNAVAILABLE,
                -> R.color.vision_status_offline
            },
        )
        binding.capabilityStatus.apply {
            setTextColor(statusColor)
            chipStrokeColor = ColorStateList.valueOf(statusColor)
            chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.vision_surface_glass),
            )
        }
        isEnabled = ui.isActionEnabled
        alpha = if (ui.isActionEnabled) 1f else 0.82f
        contentDescription = context.getString(
            R.string.capability_accessibility,
            name,
            description,
            context.getString(status),
        )
    }
}
