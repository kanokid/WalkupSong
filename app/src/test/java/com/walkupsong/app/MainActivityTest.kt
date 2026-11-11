package com.walkupsong.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28, 34], qualifiers = "w393dp-h851dp")
class MainActivityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("walkup-song", Context.MODE_PRIVATE)

    @Before fun setup() {
        SongTranscriber.resetForTesting()
        prefs.edit().clear().commit()
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("com.walkupsong.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        SongTranscriber.resetForTesting()
    }

    @Test fun firstPlayerIsSelectedAndOrderEditsPersist() {
        launch()
        assertFalse(activity.findViewById<View>(R.id.buttonPlayStop).isEnabled)
        addPlayer("Taylor Kim", "12")
        addPlayer("Jordan Lee", "4")
        assertEquals("Taylor Kim · #12", text(R.id.textViewNowBatting))
        menu(0, R.string.move_down)
        assertEquals(listOf("Jordan Lee", "Taylor Kim"), saved().map { it.name })
        assertEquals("Taylor Kim · #12", text(R.id.textViewNowBatting))
        click(R.id.buttonNext)
        assertEquals("Jordan Lee · #4", text(R.id.textViewNowBatting))
        menu(0, R.string.remove_player)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Taylor Kim · #12", text(R.id.textViewNowBatting))
        recreate()
        assertEquals(listOf("Taylor Kim"), saved().map { it.name })
        assertEquals("Taylor Kim · #12", text(R.id.textViewNowBatting))
    }

    @Test fun legacyLineupKeepsSongsAndGainsStableIds() {
        prefs.edit().putString("players", """[{"name":"Alex","number":8,"songPath":"content://song/1","startTime":9,"endTime":21,"isSelected":true}]""").commit()
        launch()
        val player = saved().single()
        assertFalse(player.id.isNullOrBlank())
        assertEquals("content://song/1", player.songPath)
        assertEquals(9, player.startTime)
        recreate()
        assertEquals(player.id, saved().single().id)
    }

    @Test fun selectingNewSongAutoTranscribesAndPopsUpSpotPicker() {
        val uri = Uri.parse("content://test/thunderstruck.mp3")
        val context = RuntimeEnvironment.getApplication()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri),
            ShadowMediaPlayer.MediaInfo(60_000, 0))
        shadowOf(context.contentResolver)
            .registerInputStreamSupplier(uri) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }

        val queued = ArrayDeque<Runnable>()
        SongTranscriber.executor = Executor { queued.addLast(it) }
        var captured: SongTranscriber.Request? = null
        SongTranscriber.transport = { request ->
            captured = request
            SongTranscriber.Response(200, """
                {
                  "text": "You've been thunderstruck yeah yeah shook me all",
                  "words": [
                    {"word": "You've", "start": 8.1, "end": 8.4},
                    {"word": "been", "start": 8.5, "end": 8.8},
                    {"word": "thunderstruck", "start": 12.1, "end": 13.4},
                    {"word": "yeah", "start": 18.1, "end": 18.6},
                    {"word": "yeah", "start": 18.7, "end": 19.6},
                    {"word": "shook", "start": 22.0, "end": 22.4},
                    {"word": "me", "start": 22.4, "end": 22.7},
                    {"word": "all", "start": 22.7, "end": 23.8}
                  ]
                }
            """.trimIndent())
        }

        prefs.edit()
            .putString("groq_api_key", "gsk_live_key_1234")
            .putString("players", Gson().toJson(listOf(
                Player("Taylor Kim", 12),
                Player("Jordan Lee", 4)
            ))).commit()

        launch()
        click(R.id.buttonSelectFile)
        val request = shadowOf(activity).nextStartedActivityForResult
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(uri))
        idle()

        // Selecting a song pops up the Pick your spot dialog and auto-starts transcription
        assertTrue(dialog().isShowing)
        assertEquals(1, queued.size)
        assertEquals(View.VISIBLE, dialog().findViewById<View>(R.id.transcribeProgress)!!.visibility)

        queued.removeFirst().run()
        idle()

        val req = captured!!
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", req.url)
        assertEquals("gsk_live_key_1234", req.apiKey)
        assertEquals("whisper-large-v3", req.model)
        assertEquals("verbose_json", req.responseFormat)
        assertEquals("word", req.timestampGranularity)

        val chips = dialog().findViewById<ChipGroup>(R.id.chipGroupWords)!!
        assertEquals(View.VISIBLE, chips.visibility)
        assertEquals(4, chips.childCount)
        assertEquals("0:08 You've been", (chips.getChildAt(0) as Chip).text.toString())
        assertEquals("0:12 thunderstruck", (chips.getChildAt(1) as Chip).text.toString())
        assertEquals("0:18 yeah yeah", (chips.getChildAt(2) as Chip).text.toString())
        assertEquals("0:22 shook me all", (chips.getChildAt(3) as Chip).text.toString())

        // Tap start group ("thunderstruck" at 12.1s..13.4s), rotate, then tap end group ("yeah yeah" at 18.1s..19.6s)
        chips.getChildAt(1).performClick()
        idle()
        assertEquals("12", dialog().findViewById<EditText>(R.id.editTextStartTime)!!.text.toString())
        assertEquals("14", dialog().findViewById<EditText>(R.id.editTextEndTime)!!.text.toString())

        recreate()
        val recreatedChips = dialog().findViewById<ChipGroup>(R.id.chipGroupWords)!!
        recreatedChips.getChildAt(2).performClick()
        idle()

        assertEquals("12", dialog().findViewById<EditText>(R.id.editTextStartTime)!!.text.toString())
        assertEquals("20", dialog().findViewById<EditText>(R.id.editTextEndTime)!!.text.toString())
        assertFalse((recreatedChips.getChildAt(0) as Chip).isChecked)
        assertTrue((recreatedChips.getChildAt(1) as Chip).isChecked)
        assertTrue((recreatedChips.getChildAt(2) as Chip).isChecked)
        assertFalse((recreatedChips.getChildAt(3) as Chip).isChecked)

        // Preview inside Change segment plays the 8s clip and stops without advancing the batter
        val previewButton = dialog().findViewById<TextView>(R.id.buttonPreviewClip)!!
        assertEquals(activity.getString(R.string.preview_clip), previewButton.text.toString())
        previewButton.performClick()
        idle()
        assertEquals(activity.getString(R.string.stop_preview), previewButton.text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(activity.getString(R.string.preview_clip), previewButton.text.toString())
        assertEquals("Taylor Kim · #12", text(R.id.textViewNowBatting))

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(12, saved()[0].startTime)
        assertEquals(20, saved()[0].endTime)
        assertEquals("0:12 – 0:20 · 8s clip", text(R.id.textViewClipTime))
    }

    @Test fun menuPickYourSpotPromptsForGroqKeyOnAuthFailure() {
        val uri = Uri.parse("content://test/song.mp3")
        shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStreamSupplier(uri) { ByteArrayInputStream(byteArrayOf(9, 8, 7)) }
        SongTranscriber.executor = Executor { it.run() }
        SongTranscriber.transport = {
            SongTranscriber.Response(401, """{"error":{"message":"Invalid API Key"}}""")
        }
        val existingWords = listOf(TimedWord("Walk", 4.0, 5.0))
        prefs.edit()
            .putString("groq_api_key", "gsk_bad")
            .putString("players", Gson().toJson(listOf(
                Player("Taylor Kim", 12, uri.toString(), "song.mp3", startTime = 4, endTime = 5, words = existingWords)
            ))).commit()

        launch()
        menu(0, R.string.edit_clip)
        assertTrue(dialog().isShowing)
        dialog().findViewById<View>(R.id.buttonTranscribeClip)!!.performClick()
        idle()
        assertEquals(activity.getString(R.string.groq_auth_error), ShadowToast.getTextOfLatestToast())
        assertNotNull(dialog().findViewById<EditText>(R.id.editTextGroqKey))
        assertEquals(existingWords, saved()[0].words)
    }

    @Test fun importedClipRejectsInvalidTimesAndKeepsItsOriginalTarget() {
        seed()
        launch()
        click(R.id.buttonSelectFile)
        val request = shadowOf(activity).nextStartedActivityForResult
        recreate()
        click(R.id.buttonNext)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://test/music")))
        idle()
        val start = dialog().findViewById<EditText>(R.id.editTextStartTime)!!
        val end = dialog().findViewById<EditText>(R.id.editTextEndTime)!!
        start.setText("9")
        for (invalid in listOf("", "9", "8", "2147483647")) {
            end.setText(invalid)
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog().isShowing)
            assertNull(saved()[0].songPath)
        }
        end.setText("23")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("content://test/music", saved()[0].songPath)
        assertEquals(9, saved()[0].startTime)
        assertEquals(23, saved()[0].endTime)
        assertNull(saved()[1].songPath)
    }

    @Test fun corruptSavedDataIsNotOverwritten() {
        prefs.edit().putString("players", "not json").commit()
        launch()
        assertFalse(activity.findViewById<View>(R.id.buttonAddPlayer).isEnabled)
        assertEquals("not json", prefs.getString("players", null))
    }

    @Test fun onlyClipCompletionAdvancesAndLeavingAppStopsAudio() {
        val uri = Uri.parse("content://test/song")
        val context = RuntimeEnvironment.getApplication()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri),
            ShadowMediaPlayer.MediaInfo(60_000, 0))
        prefs.edit().putString("players", Gson().toJson(listOf(
            Player("Taylor", 12, uri.toString(), startTime = 7, endTime = 10),
            Player("Jordan", 4, uri.toString(), startTime = 15, endTime = 20)
        ))).commit()
        launch()
        click(R.id.buttonPlayStop)
        assertEquals("ON THE SPEAKERS", text(R.id.textViewPlaybackState))
        click(R.id.buttonStop)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("Taylor · #12", text(R.id.textViewNowBatting))
        click(R.id.buttonPlayStop)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("Jordan · #4", text(R.id.textViewNowBatting))
        assertEquals("NOW BATTING", text(R.id.textViewPlaybackState))
        click(R.id.buttonPlayStop)
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        controller.start().resume()
        assertEquals("Jordan · #4", text(R.id.textViewNowBatting))
        assertEquals("NOW BATTING", text(R.id.textViewPlaybackState))
    }

    private fun seed() {
        prefs.edit().putString("players", Gson().toJson(listOf(
            Player("Taylor Kim", 12),
            Player("Jordan Lee", 4)
        ))).commit()
    }

    private fun launch() { controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible(); idle() }
    private fun recreate() {
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
        idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click(id: Int) { activity.findViewById<View>(id).performClick(); idle() }
    private fun text(id: Int) = activity.findViewById<TextView>(id).text.toString()
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun saved(): List<Player> = Gson().fromJson(prefs.getString("players", null), object : TypeToken<List<Player>>() {}.type)
    private fun addPlayer(name: String, number: String) {
        click(R.id.buttonAddPlayer)
        dialog().findViewById<EditText>(R.id.editTextPlayerName)!!.setText(name)
        dialog().findViewById<EditText>(R.id.editTextPlayerNumber)!!.setText(number)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }
    private fun menu(position: Int, action: Int) {
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerViewBattingOrder)
        recycler.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        recycler.layout(0, 0, 393, recycler.measuredHeight)
        recycler.findViewHolderForAdapterPosition(position)!!.itemView
            .findViewById<View>(R.id.buttonPlayerMenu).performClick()
        idle()
        val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        fun list(view: View): ListView? = if (view is ListView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { list(view.getChildAt(it)) } else null
        val items = list(popup.contentView)!!
        val index = (0 until items.count).first {
            (items.getItemAtPosition(it) as android.view.MenuItem).itemId == action
        }
        items.performItemClick(items.getChildAt(index), index, items.adapter.getItemId(index))
        idle()
    }
}
