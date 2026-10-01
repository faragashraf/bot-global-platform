package com.ashraffarag.sentricam.live.android

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.live.presentation.LiveStatusKind
import com.ashraffarag.sentricam.live.presentation.LiveStatusPresentation
import com.google.android.material.card.MaterialCardView

class LiveStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val icon: ImageView
    private val status: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_live_status, this, true)
        icon = findViewById(R.id.live_status_icon)
        status = findViewById(R.id.live_status_text)
    }

    fun render(value: LiveStatusPresentation) {
        val visual = visual(value.kind)
        val label = context.getString(visual.label)
        status.text = label
        val color = ContextCompat.getColor(context, visual.color)
        status.setTextColor(color)
        icon.imageTintList = ContextCompat.getColorStateList(context, visual.color)
        strokeColor = color
        setCardBackgroundColor(ContextCompat.getColor(context, visual.containerColor))
        icon.contentDescription = context.getString(
            R.string.live_status_accessibility,
            status.text,
        )
    }

    private fun visual(kind: LiveStatusKind): Visual = when (kind) {
        LiveStatusKind.IDLE -> Visual(
            R.string.live_status_idle,
            R.color.vision_status_offline,
            R.color.vision_status_offline_container,
        )
        LiveStatusKind.CONNECTING -> Visual(
            R.string.live_status_connecting,
            R.color.vision_brand_primary,
            R.color.vision_brand_primary_container,
        )
        LiveStatusKind.STREAMING -> Visual(
            R.string.live_status_streaming,
            R.color.vision_status_healthy,
            R.color.vision_status_healthy_container,
        )
        LiveStatusKind.RECONNECTING -> Visual(
            R.string.live_status_reconnecting,
            R.color.vision_status_motion,
            R.color.vision_status_motion_container,
        )
        LiveStatusKind.FAILED -> Visual(
            R.string.live_status_failed,
            R.color.vision_status_critical,
            R.color.vision_status_critical_container,
        )
    }

    private data class Visual(
        @param:StringRes val label: Int,
        @param:ColorRes val color: Int,
        @param:ColorRes val containerColor: Int,
    )
}
