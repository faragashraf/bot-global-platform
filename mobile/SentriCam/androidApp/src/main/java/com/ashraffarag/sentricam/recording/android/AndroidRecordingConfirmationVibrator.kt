package com.ashraffarag.sentricam.recording.android

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ashraffarag.sentricam.recording.presentation.RecordingConfirmationVibrator

class AndroidRecordingConfirmationVibrator(
    context: Context,
) : RecordingConfirmationVibrator {
    private val applicationContext = context.applicationContext

    override fun vibrate() {
        val vibrator = applicationContext.recordingVibrator()
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    CONFIRMATION_DURATION_MILLIS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(CONFIRMATION_DURATION_MILLIS)
        }
    }

    private fun Context.recordingVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private companion object {
        const val CONFIRMATION_DURATION_MILLIS = 70L
    }
}
