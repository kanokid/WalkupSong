package com.walkupsong.app

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28, 34])
class ClipPlaybackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var playback: ClipPlayback
    private var completed = 0
    private val errors = mutableListOf<Int>()
    private var elapsed = -1
    private lateinit var media: MediaPlayer
    private val player = Player("Taylor", 12, "content://test/song", startTime = 7, endTime = 10)

    @Before fun setup() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, Uri.parse(player.songPath)),
            ShadowMediaPlayer.MediaInfo(60_000, 200))
        ShadowMediaPlayer.setCreateListener { created, shadow ->
            media = created
            shadow.setSeekDelay(300)
        }
        playback = ClipPlayback(context, {}, { position, _ -> elapsed = position },
            { completed++ }, { errors.add(it) })
    }

    @After fun cleanup() = playback.stop()

    @Test fun clipWaitsForSeekAndPauseDoesNotConsumeClipTime() {
        playback.play(player)
        advance(499)
        assertEquals(ClipPlayback.State.LOADING, playback.state)
        assertFalse(media.isPlaying)
        advance(1)
        assertEquals(ClipPlayback.State.PLAYING, playback.state)
        assertEquals(7000, media.currentPosition)
        advance(1000)
        assertEquals(1000, elapsed)
        playback.pause()
        advance(8000)
        assertEquals(ClipPlayback.State.PAUSED, playback.state)
        assertEquals(0, completed)
        playback.resume()
        advance(1999)
        assertEquals(ClipPlayback.State.PLAYING, playback.state)
        advance(1)
        assertEquals(ClipPlayback.State.IDLE, playback.state)
        assertEquals(1, completed)
        assertTrue(errors.isEmpty())
        advance(60_000)
        assertEquals(1, completed)
    }

    @Test fun stopDuringPreparationNeverStartsOrCompletes() {
        playback.play(player)
        playback.stop()
        advance(60_000)
        assertEquals(ClipPlayback.State.IDLE, playback.state)
        assertEquals(0, completed)
        assertTrue(errors.isEmpty())
    }

    @Test fun replacingClipCancelsOldDeadline() {
        playback.play(player)
        advance(1500)
        playback.play(player.copy(startTime = 20, endTime = 25))
        advance(2499)
        assertEquals(ClipPlayback.State.PLAYING, playback.state)
        assertEquals(0, completed)
        advance(3001)
        assertEquals(1, completed)
    }

    @Test fun invalidAndOutOfFileRangesFailWithoutAdvancing() {
        listOf(-1 to 10, 7 to 7, 10 to 7, 0 to Int.MAX_VALUE).forEach { (start, end) ->
            playback.play(player.copy(startTime = start, endTime = end))
            assertEquals(R.string.invalid_clip, errors.last())
            assertEquals(ClipPlayback.State.IDLE, playback.state)
        }
        playback.play(player.copy(startTime = 59, endTime = 61))
        advance(500)
        assertEquals(R.string.clip_too_long, errors.last())
        assertEquals(ClipPlayback.State.IDLE, playback.state)
        assertEquals(0, completed)
        playback.play(player.copy(startTime = 59, endTime = 60))
        advance(1500)
        assertEquals(1, completed)
    }

    @Test fun unreadableFileAndDecoderErrorCanBeRetried() {
        playback.play(player.copy(songPath = "content://test/missing"))
        assertEquals(ClipPlayback.State.IDLE, playback.state)
        assertEquals(R.string.audio_error, errors.last())
        playback.play(player)
        advance(500)
        shadowOf(media).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(ClipPlayback.State.IDLE, playback.state)
        assertEquals(0, completed)
        playback.play(player)
        advance(3500)
        assertEquals(1, completed)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
