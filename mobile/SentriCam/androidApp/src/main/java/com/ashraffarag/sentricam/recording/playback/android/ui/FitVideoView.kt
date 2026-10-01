package com.ashraffarag.sentricam.recording.playback.android.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.VideoView
import com.ashraffarag.sentricam.recording.playback.domain.VideoDisplayLayout

class FitVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VideoView(context, attrs, defStyleAttr) {
    private var displayWidthPx = 0
    private var displayHeightPx = 0

    fun setDisplayDimensions(widthPx: Int, heightPx: Int) {
        if (displayWidthPx == widthPx && displayHeightPx == heightPx) return
        displayWidthPx = widthPx
        displayHeightPx = heightPx
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val content = VideoDisplayLayout.fullScreenContent(
            availableWidthPx = availableWidth,
            availableHeightPx = availableHeight,
            videoWidthPx = displayWidthPx,
            videoHeightPx = displayHeightPx,
        )
        setMeasuredDimension(content.widthPx, content.heightPx)
    }
}
