package com.ashraffarag.sentricam.recording.library.android.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ViewRecordingDetailBinding

class RecordingDetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val binding = ViewRecordingDetailBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        context.obtainStyledAttributes(attrs, R.styleable.RecordingDetailView).apply {
            binding.detailLabel.text = getText(R.styleable.RecordingDetailView_detailLabel)
            recycle()
        }
    }

    fun setValue(value: CharSequence) {
        binding.detailValue.text = value
    }
}
