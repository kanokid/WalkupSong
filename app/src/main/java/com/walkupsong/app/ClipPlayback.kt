package com.walkupsong.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes

/** Owns one clip. Selection, cancellation and lifecycle cleanup never advance the lineup. */
@Suppress("DEPRECATION")
class ClipPlayback(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onProgress: (elapsedMs: Int, durationMs: Int) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (Int) -> Unit
) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED }

    var state = State.IDLE
        private set
    private var media: MediaPlayer? = null
    private var startMs = 0
    private var endMs = 0
    private var advanceOnComplete = true
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) stop()
    }

    private val tick = object : Runnable {
        override fun run() {
            val player = media ?: return
            if (state != State.PLAYING) return
            val position = player.currentPosition
            if (position >= endMs) {
                complete()
            } else {
                onProgress((position - startMs).coerceAtLeast(0), endMs - startMs)
                handler.postDelayed(this, minOf(50, endMs - position).toLong())
            }
        }
    }

    fun play(player: Player, advanceOnComplete: Boolean = true) {
        stop()
        val path = player.songPath ?: return
        if (player.startTime < 0 || player.endTime <= player.startTime ||
            player.endTime > Int.MAX_VALUE / 1000) {
            fail(R.string.invalid_clip)
            return
        }
        this.advanceOnComplete = advanceOnComplete
        startMs = player.startTime * 1000
        endMs = player.endTime * 1000
        if (audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(R.string.focus_error)
            return
        }
        val current = MediaPlayer()
        media = current
        state = State.LOADING
        onChanged()
        try {
            current.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            current.setOnErrorListener { source, _, _ ->
                if (media === source) fail(R.string.audio_error)
                true
            }
            current.setOnCompletionListener { source ->
                if (media === source) complete()
            }
            current.setOnPreparedListener { source ->
                if (media === source) {
                    if (endMs > source.duration || startMs >= source.duration) {
                        fail(R.string.clip_too_long)
                    } else {
                        source.setOnSeekCompleteListener { seeked ->
                            if (media === seeked && state == State.LOADING) {
                                resume()
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            source.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else {
                            source.seekTo(startMs)
                        }
                    }
                }
            }
            current.setDataSource(context, Uri.parse(path))
            current.prepareAsync()
        } catch (_: Exception) {
            fail(R.string.audio_error)
        }
    }

    fun pause() {
        if (state != State.PLAYING) return
        media?.pause()
        handler.removeCallbacks(tick)
        state = State.PAUSED
        onChanged()
    }

    fun resume() {
        if (state != State.PAUSED && state != State.LOADING) return
        media?.start()
        state = State.PLAYING
        onChanged()
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val previous = media
        media = null
        previous?.release()
        audioManager.abandonAudioFocus(focusListener)
        state = State.IDLE
        onChanged()
    }

    private fun complete() {
        val shouldAdvance = advanceOnComplete
        stop()
        if (shouldAdvance) onComplete()
    }

    private fun fail(@StringRes message: Int) {
        stop()
        onError(message)
    }
}
