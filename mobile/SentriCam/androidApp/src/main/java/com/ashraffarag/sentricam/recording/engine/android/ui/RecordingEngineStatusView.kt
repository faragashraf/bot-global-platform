package com.ashraffarag.sentricam.recording.engine.android.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ViewRecordingEngineStatusBinding
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineStatusKind
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineStatusUi
import com.google.android.material.card.MaterialCardView

class RecordingEngineStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val binding = ViewRecordingEngineStatusBinding.inflate(
        LayoutInflater.from(context),
        this,
        true,
    )

    fun render(model: RecordingEngineStatusUi) {
        val summaryVisible = model.visible && resources.getBoolean(R.bool.show_recording_engine_summary)
        visibility = if (summaryVisible) View.VISIBLE else View.GONE
        if (!summaryVisible) return

        binding.engineStatus.text = when (model.kind) {
            RecordingEngineStatusKind.STARTING -> context.getString(R.string.engine_overlay_starting)
            RecordingEngineStatusKind.RECORDING -> context.getString(
                R.string.engine_overlay_recording_segment,
                model.segmentIndex,
            )
            RecordingEngineStatusKind.SAVING_SEGMENT -> context.getString(
                R.string.engine_overlay_saving_segment,
                model.segmentIndex,
            )
            RecordingEngineStatusKind.STOPPING -> context.getString(R.string.engine_overlay_stopping)
            null -> ""
        }
        binding.engineDurations.text = context.getString(
            R.string.engine_overlay_durations,
            RecordingEngineUiFormatter.duration(model.sessionDurationMillis),
            RecordingEngineUiFormatter.duration(model.segmentDurationMillis),
        )
        binding.engineDetails.text = context.getString(
            R.string.engine_overlay_details,
            qualityLabel(model.quality),
            lensLabel(model.lens),
        )
        binding.engineStorage.text = context.getString(
            R.string.engine_overlay_storage,
            RecordingEngineUiFormatter.storageSize(model.availableStorageBytes),
        )
        binding.engineStorage.setTextColor(
            context.getColor(
                if (model.storageLevel == RecordingStorageLevel.HEALTHY) {
                    R.color.white
                } else {
                    R.color.recording_stopping
                },
            ),
        )
    }

    private fun qualityLabel(quality: RecordingQuality?): String = context.getString(
        when (quality) {
            RecordingQuality.SD -> R.string.engine_quality_actual_sd
            RecordingQuality.HD -> R.string.engine_quality_actual_hd
            RecordingQuality.FULL_HD -> R.string.engine_quality_actual_full_hd
            null -> R.string.library_value_unknown
        },
    )

    private fun lensLabel(lens: RecordingLens?): String = context.getString(
        when (lens) {
            RecordingLens.BACK -> R.string.engine_lens_back
            RecordingLens.FRONT -> R.string.engine_lens_front
            null -> R.string.library_value_unknown
        },
    )
}
