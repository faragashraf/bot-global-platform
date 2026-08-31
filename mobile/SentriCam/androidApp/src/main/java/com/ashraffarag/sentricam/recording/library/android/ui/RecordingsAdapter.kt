package com.ashraffarag.sentricam.recording.library.android.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ItemRecordingCardBinding
import com.ashraffarag.sentricam.databinding.ItemRecordingGroupHeaderBinding
import com.ashraffarag.sentricam.recording.library.android.thumbnail.RecordingThumbnailLoader
import com.ashraffarag.sentricam.recording.library.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.library.domain.RecordingDateGroup
import com.ashraffarag.sentricam.recording.library.domain.RecordingEntry
import com.ashraffarag.sentricam.recording.library.domain.RecordingSection
import com.ashraffarag.sentricam.recording.library.domain.RecordingSessionDisplayInfo
import com.ashraffarag.sentricam.recording.library.domain.RecordingSessionGrouping
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.google.android.material.chip.Chip

class RecordingsAdapter(
    private val thumbnailLoader: RecordingThumbnailLoader,
    private val onRecordingSelected: (RecordingEntry) -> Unit,
) : ListAdapter<RecordingsAdapter.Row, RecyclerView.ViewHolder>(RowDiff) {
    private var sessionInfoByEntryId: Map<String, RecordingSessionDisplayInfo> = emptyMap()

    fun submitSections(sections: List<RecordingSection>) {
        sessionInfoByEntryId = RecordingSessionGrouping.build(
            sections.flatMap(RecordingSection::recordings),
        )
        submitList(
            sections.flatMap { section ->
                listOf<Row>(Row.Header(section.group)) + section.recordings.map(Row::Recording)
            },
        )
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Recording -> VIEW_TYPE_RECORDING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemRecordingGroupHeaderBinding.inflate(inflater, parent, false),
            )

            else -> RecordingViewHolder(
                ItemRecordingCardBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind((getItem(position) as Row.Header).group)
            is RecordingViewHolder -> {
                val entry = (getItem(position) as Row.Recording).entry
                holder.bind(entry, sessionInfoByEntryId[entry.id])
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RecordingViewHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    fun isHeader(position: Int): Boolean = getItemViewType(position) == VIEW_TYPE_HEADER

    private class HeaderViewHolder(
        private val binding: ItemRecordingGroupHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: RecordingDateGroup) {
            binding.groupTitle.setText(
                when (group) {
                    RecordingDateGroup.TODAY -> R.string.library_group_today
                    RecordingDateGroup.YESTERDAY -> R.string.library_group_yesterday
                    RecordingDateGroup.OLDER -> R.string.library_group_older
                },
            )
        }
    }

    private inner class RecordingViewHolder(
        private val binding: ItemRecordingCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: RecordingEntry, sessionInfo: RecordingSessionDisplayInfo?) {
            binding.thumbnail.tag = entry.id
            binding.thumbnail.setImageResource(R.drawable.ic_video_placeholder)
            binding.recordingTime.text = RecordingLibraryUiFormatter.dateTime(
                entry.metadata.startedAtMillis,
            )
            binding.recordingDuration.text = RecordingLibraryUiFormatter.duration(entry.metadata.duration)
            binding.recordingSession.text = when {
                sessionInfo?.shortSessionId == null -> binding.root.context.getString(
                    R.string.library_session_legacy,
                )
                sessionInfo.isMultiSegment -> binding.root.context.getString(
                    R.string.library_session_multi,
                    sessionInfo.shortSessionId,
                    sessionInfo.segmentCount,
                )
                else -> binding.root.context.getString(
                    R.string.library_session_single,
                    sessionInfo.shortSessionId,
                )
            }
            binding.recordingSegment.text = sessionInfo?.segmentIndex?.let { index ->
                binding.root.context.getString(
                    R.string.library_segment_position,
                    index,
                    sessionInfo.segmentCount,
                )
            } ?: binding.root.context.getString(R.string.library_segment_unknown)
            binding.recordingQuality.text = when (entry.metadata.actualQuality) {
                RecordingQuality.SD -> binding.root.context.getString(R.string.engine_quality_actual_sd)
                RecordingQuality.HD -> binding.root.context.getString(R.string.engine_quality_actual_hd)
                RecordingQuality.FULL_HD -> binding.root.context.getString(
                    R.string.engine_quality_actual_full_hd,
                )
                null -> binding.root.context.getString(R.string.library_value_unknown)
            }
            val recordingFailed = entry.metadata.completionStatus == RecordingSegmentStatus.FAILED
            binding.recordingCompletion.visibility = if (recordingFailed) View.VISIBLE else View.GONE
            binding.recordingCompletion.setText(R.string.library_completion_failed)
            when (entry.metadata.startReason) {
                RecordingStartReason.MOTION -> {
                    binding.recordingTrigger.setText(R.string.library_trigger_motion_label)
                    binding.recordingTrigger.applyPalette(
                        R.color.vision_status_motion,
                        R.color.vision_status_motion_container,
                    )
                }

                RecordingStartReason.MANUAL -> {
                    binding.recordingTrigger.setText(R.string.library_trigger_manual_label)
                    binding.recordingTrigger.applyPalette(
                        R.color.vision_brand_primary,
                        R.color.vision_brand_primary_container,
                    )
                }

                RecordingStartReason.REMOTE -> {
                    binding.recordingTrigger.setText(R.string.library_trigger_remote_label)
                    binding.recordingTrigger.applyPalette(
                        R.color.vision_brand_secondary,
                        R.color.vision_brand_secondary_container,
                    )
                }

                null -> {
                    binding.recordingTrigger.setText(R.string.library_value_unknown)
                    binding.recordingTrigger.applyPalette(
                        R.color.vision_status_offline,
                        R.color.vision_status_offline_container,
                    )
                }
            }
            val displayDimensions = entry.metadata.displayDimensions
            binding.recordingResolution.text = binding.root.context.getString(
                R.string.library_resolution_value,
                RecordingLibraryUiFormatter.resolution(
                    displayDimensions.width,
                    displayDimensions.height,
                ),
            )
            binding.recordingFileSize.text = binding.root.context.getString(
                R.string.library_size_value,
                RecordingLibraryUiFormatter.fileSize(entry.storage.sizeBytes),
            )
            val camera = binding.root.context.getString(
                when (entry.metadata.camera) {
                    RecordingCamera.REAR -> R.string.library_camera_rear
                    RecordingCamera.FRONT -> R.string.library_camera_front
                    RecordingCamera.UNKNOWN -> R.string.library_value_unknown
                },
            )
            binding.recordingCamera.text = binding.root.context.getString(
                R.string.library_camera_value,
                camera,
            )
            val audio = binding.root.context.getString(
                if (entry.metadata.audioEnabled) R.string.library_enabled else R.string.library_disabled,
            )
            binding.recordingAudio.text = binding.root.context.getString(R.string.library_audio_value, audio)
            val timestamp = binding.root.context.getString(
                when (entry.metadata.timestampOverlayEnabled) {
                    true -> R.string.library_enabled
                    false -> R.string.library_disabled
                    null -> R.string.library_value_unknown
                },
            )
            binding.recordingTimestamp.text = binding.root.context.getString(
                R.string.library_timestamp_value,
                timestamp,
            )
            binding.root.setOnClickListener { onRecordingSelected(entry) }

            thumbnailLoader.load(entry) { bitmap ->
                if (binding.thumbnail.tag == entry.id && bitmap != null) {
                    binding.thumbnail.setImageBitmap(bitmap)
                }
            }
        }

        fun recycle() {
            binding.thumbnail.tag = null
            binding.thumbnail.setImageResource(R.drawable.ic_video_placeholder)
            binding.root.setOnClickListener(null)
        }

        private fun Chip.applyPalette(
            @ColorRes contentColor: Int,
            @ColorRes containerColor: Int,
        ) {
            val content = ContextCompat.getColor(context, contentColor)
            setTextColor(content)
            chipStrokeColor = ColorStateList.valueOf(content)
            chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, containerColor))
        }
    }

    sealed interface Row {
        data class Header(val group: RecordingDateGroup) : Row

        data class Recording(val entry: RecordingEntry) : Row
    }

    private object RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Header && newItem is Row.Header -> oldItem.group == newItem.group
            oldItem is Row.Recording && newItem is Row.Recording -> oldItem.entry.id == newItem.entry.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_RECORDING = 1
    }
}
