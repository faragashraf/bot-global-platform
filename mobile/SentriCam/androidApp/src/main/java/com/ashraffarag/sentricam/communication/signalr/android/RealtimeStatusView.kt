package com.ashraffarag.sentricam.communication.signalr.android

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.communication.signalr.presentation.RealtimeStatusKind
import com.ashraffarag.sentricam.communication.signalr.presentation.RealtimeStatusPresentation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class RealtimeStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val statusIcon: ImageView
    private val statusText: TextView
    private val summaryText: TextView
    private val detailContainer: LinearLayout
    private val detailText: TextView
    private val reconnectButton: MaterialButton
    private val detailButton: MaterialButton
    private var expanded = false
    private var development = false
    private var localCamera = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_realtime_status, this, true)
        statusIcon = findViewById(R.id.realtime_status_icon)
        statusText = findViewById(R.id.realtime_status_text)
        summaryText = findViewById(R.id.realtime_status_summary)
        detailContainer = findViewById(R.id.realtime_status_detail_container)
        detailText = findViewById(R.id.realtime_status_detail)
        reconnectButton = findViewById(R.id.realtime_reconnect)
        detailButton = findViewById(R.id.realtime_detail_toggle)
        detailButton.setOnClickListener {
            expanded = !expanded
            updateDetailVisibility()
        }
    }

    fun setReconnectAction(action: () -> Unit) {
        reconnectButton.setOnClickListener { action() }
    }

    fun render(value: RealtimeStatusPresentation, isDevelopment: Boolean) {
        development = isDevelopment
        val visual = visual(value.kind)
        localCamera = value.kind == RealtimeStatusKind.LOCAL_CAMERA
        statusIcon.setImageResource(visual.icon)
        statusText.setText(visual.label)
        statusText.setTextColor(ContextCompat.getColor(context, visual.color))
        statusIcon.imageTintList = ContextCompat.getColorStateList(context, visual.color)
        strokeColor = ContextCompat.getColor(context, visual.color)
        setCardBackgroundColor(ContextCompat.getColor(context, visual.containerColor))
        statusIcon.contentDescription = context.getString(visual.label)
        summaryText.text = if (localCamera) {
            context.getString(R.string.camera_operating_mode_local_summary)
        } else {
            context.getString(
                R.string.realtime_status_summary,
                value.hubHost,
                value.lastConnectedAtMillis?.let(::formatTime)
                    ?: context.getString(R.string.realtime_never_connected),
            )
        }
        detailText.text = context.getString(
            R.string.realtime_status_diagnostic,
            value.reconnectAttempt,
            value.reasonCode ?: context.getString(R.string.realtime_no_failure),
        )
        reconnectButton.visibility = if (value.canReconnectNow) View.VISIBLE else View.GONE
        detailButton.visibility = if (development && !localCamera) View.VISIBLE else View.GONE
        updateDetailVisibility()
    }

    private fun updateDetailVisibility() {
        detailContainer.visibility = if (development && expanded && !localCamera) View.VISIBLE else View.GONE
        detailButton.contentDescription = context.getString(
            if (expanded) R.string.realtime_hide_details else R.string.realtime_show_details,
        )
    }

    private fun formatTime(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

    private fun visual(kind: RealtimeStatusKind): Visual = when (kind) {
        RealtimeStatusKind.LOCAL_CAMERA -> Visual(
            R.string.camera_operating_mode_local,
            R.color.vision_brand_primary,
            R.color.vision_brand_primary_container,
            R.drawable.ic_flip_camera_24,
        )
        RealtimeStatusKind.CONNECTED -> Visual(
            R.string.signalr_connected,
            R.color.vision_status_healthy,
            R.color.vision_status_healthy_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.CONNECTING -> Visual(
            R.string.signalr_connecting,
            R.color.vision_brand_primary,
            R.color.vision_brand_primary_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.RECONNECTING -> Visual(
            R.string.signalr_reconnecting,
            R.color.vision_status_motion,
            R.color.vision_status_motion_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.OFFLINE -> Visual(
            R.string.signalr_offline,
            R.color.vision_status_offline,
            R.color.vision_status_offline_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.SERVER_UNREACHABLE -> Visual(
            R.string.signalr_server_unavailable,
            R.color.vision_status_motion,
            R.color.vision_status_motion_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.AUTHENTICATION_FAILED -> Visual(
            R.string.signalr_authentication_failed,
            R.color.vision_status_critical,
            R.color.vision_status_critical_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.PROTOCOL_ERROR -> Visual(
            R.string.signalr_protocol_error,
            R.color.vision_status_critical,
            R.color.vision_status_critical_container,
            R.drawable.ic_hub_24,
        )
        RealtimeStatusKind.SERVICE_STOPPED -> Visual(
            R.string.signalr_service_stopped,
            R.color.vision_status_offline,
            R.color.vision_status_offline_container,
            R.drawable.ic_hub_24,
        )
    }

    private data class Visual(
        @param:StringRes val label: Int,
        @param:ColorRes val color: Int,
        @param:ColorRes val containerColor: Int,
        val icon: Int,
    )
}
