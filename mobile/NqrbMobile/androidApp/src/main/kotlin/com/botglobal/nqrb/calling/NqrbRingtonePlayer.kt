package com.botglobal.nqrb.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.botglobal.nqrb.R
import com.botglobal.nqrb.app.state.NqrbRingtone

/** Plays the app's own incoming-call tones without depending on mutable channel sounds. */
class NqrbRingtonePlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(ringtone: NqrbRingtone, looping: Boolean, deviceToneUri: String? = null) {
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.getStreamVolume(AudioManager.STREAM_RING) == 0
        ) return

        val resource = when (ringtone) {
            NqrbRingtone.Madar -> R.raw.nqrb_ring_madar
            NqrbRingtone.Gentle -> R.raw.nqrb_ring_gentle
            NqrbRingtone.Classic -> R.raw.nqrb_ring_classic
            NqrbRingtone.Clear -> R.raw.nqrb_ring_clear
            NqrbRingtone.Pulse -> R.raw.nqrb_ring_pulse
            NqrbRingtone.Device -> null
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching {
            val media = if (resource != null) {
                MediaPlayer.create(context, resource, attributes, 0)
            } else {
                val uri = deviceToneUri?.let(Uri::parse)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                runCatching {
                    MediaPlayer().apply {
                        try {
                            setAudioAttributes(attributes)
                            setDataSource(context, uri)
                            prepare()
                        } catch (error: Exception) {
                            release()
                            throw error
                        }
                    }
                }.getOrNull() ?: MediaPlayer.create(context, R.raw.nqrb_ring_madar, attributes, 0)
            }
            media?.also {
                media.isLooping = looping
                media.setOnCompletionListener { if (!looping) stop() }
                media.start()
                player = media
            }
        }.onFailure {
            Log.w("NqrbRingtone", "Unable to play ringtone type=${it::class.simpleName}")
            stop()
        }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }
}
