package com.walkupsong.app

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

class MainActivity : AppCompatActivity() {
    private val players = mutableListOf<Player>()
    private lateinit var playerAdapter: PlayerAdapter
    private lateinit var playback: ClipPlayback
    private var selectedPlayer: Player? = null
    private var pendingFilePlayerId: String? = null
    private var anchorWordIndex: Int? = null
    private var dataReadable = true
    private val preferences by lazy { getSharedPreferences("walkup-song", Context.MODE_PRIVATE) }
    private var editor: AlertDialog? = null
    private var editorKind: String? = null
    private var editorPlayerId: String? = null
    private var editorUri: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) playback.stop()
        }
    }

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = players.find { it.id == pendingFilePlayerId }
        pendingFilePlayerId = null
        if (uri != null && target != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                message(R.string.file_temporary)
            }
            if (target.songPath != uri.toString()) {
                target.words = null
            }
            showClipEditor(target, uri, autoTranscribe = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        volumeControlStream = AudioManager.STREAM_MUSIC
        loadPlayers()
        pendingFilePlayerId = savedInstanceState?.getString("pendingFile")
        anchorWordIndex = savedInstanceState?.getInt("anchorWord", -1)?.takeIf { it >= 0 }
        playerAdapter = PlayerAdapter(players, ::selectPlayer, ::showPlayerMenu)
        findViewById<RecyclerView>(R.id.recyclerViewBattingOrder).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playerAdapter
        }
        playback = ClipPlayback(this, ::renderPlayback, ::renderProgress, ::nextPlayer, ::message)
        findViewById<View>(R.id.buttonAddPlayer).setOnClickListener { showPlayerEditor() }
        findViewById<View>(R.id.buttonSelectFile).setOnClickListener {
            selectedPlayer?.let { chooseFile(it) }
        }
        findViewById<View>(R.id.buttonPlayStop).setOnClickListener {
            when (playback.state) {
                ClipPlayback.State.PLAYING -> playback.pause()
                ClipPlayback.State.PAUSED -> playback.resume()
                ClipPlayback.State.IDLE -> selectedPlayer?.let { playback.play(it) }
                ClipPlayback.State.LOADING -> Unit
            }
        }
        findViewById<View>(R.id.buttonStop).setOnClickListener { playback.stop() }
        findViewById<View>(R.id.buttonNext).setOnClickListener { nextPlayer() }
        SongTranscriber.attach(::onTranscriberStateChanged, ::handleSongTranscription)
        refresh()
        if (!dataReadable) message(R.string.saved_data_error)
        savedInstanceState?.let { state ->
            val player = players.find { it.id == state.getString("editorPlayer") }
            val uri = state.getString("editorUri")?.let(Uri::parse)
            when (state.getString("editorKind")) {
                "player" -> showPlayerEditor(player, state)
                "clip" -> if (player != null && uri != null) {
                    showClipEditor(player, uri, state, autoTranscribe = false)
                }
                "groq_key" -> showGroqKeyEditor(player, uri, state.getString("groqKey"))
                else -> Unit
            }
        }
    }

    private fun loadPlayers() {
        try {
            val type = object : TypeToken<List<Player?>>() {}.type
            val saved: List<Player?> = Gson().fromJson(preferences.getString("players", null), type) ?: emptyList()
            require(saved.all { it != null && !it.name.isNullOrBlank() })
            saved.filterNotNull().forEach {
                if (it.id.isNullOrBlank()) it.id = UUID.randomUUID().toString()
                players.add(it)
            }
            selectedPlayer = players.find { it.id == preferences.getString("selected_player", null) }
                ?: players.firstOrNull()
            savePlayers()
        } catch (_: RuntimeException) {
            players.clear()
            dataReadable = false
        }
    }

    private fun savePlayers() {
        if (dataReadable) preferences.edit()
            .putString("players", Gson().toJson(players))
            .putString("selected_player", selectedPlayer?.id).apply()
    }

    private fun selectPlayer(player: Player) {
        playback.stop()
        selectedPlayer = player
        savePlayers()
        refresh()
    }

    private fun nextPlayer() {
        if (players.isEmpty()) return
        val next = (players.indexOf(selectedPlayer) + 1) % players.size
        selectPlayer(players[next])
    }

    private fun refresh() {
        players.forEach { it.isSelected = it.id == selectedPlayer?.id }
        playerAdapter.notifyDataSetChanged()
        val player = selectedPlayer
        findViewById<TextView>(R.id.textViewNowBatting).text = player?.let {
            getString(R.string.player_heading, it.name, it.number)
        } ?: getString(R.string.ready_when_you_are)
        findViewById<TextView>(R.id.textViewNowSong).text =
            player?.songTitle ?: getString(if (player == null) R.string.start_lineup else R.string.no_song)
        findViewById<TextView>(R.id.textViewLineupTitle).text = getString(R.string.lineup_count, players.size)
        findViewById<TextView>(R.id.textViewEmpty).isVisible = players.isEmpty()
        findViewById<View>(R.id.buttonAddPlayer).isEnabled = dataReadable
        findViewById<MaterialButton>(R.id.buttonSelectFile).apply {
            isEnabled = player != null
            setText(if (player?.songPath == null) R.string.choose_song else R.string.change_song)
        }
        findViewById<View>(R.id.buttonNext).isEnabled = players.isNotEmpty()
        renderPlayback()
    }

    private fun onTranscriberStateChanged() {
        if (editorKind == "clip") {
            val player = players.find { it.id == editorPlayerId }
            if (player != null) renderClipDialog(player)
        }
    }

    private fun renderPlayback() {
        val state = playback.state
        findViewById<MaterialButton>(R.id.buttonPlayStop).apply {
            isEnabled = selectedPlayer?.songPath != null && state != ClipPlayback.State.LOADING
            val label = getString(if (state == ClipPlayback.State.PLAYING) R.string.pause else R.string.play)
            val compact = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.2f
            text = if (compact) "" else label
            contentDescription = label
            setIconResource(if (state == ClipPlayback.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        }
        findViewById<View>(R.id.buttonStop).isEnabled = state != ClipPlayback.State.IDLE
        findViewById<TextView>(R.id.textViewPlaybackState).setText(when (state) {
            ClipPlayback.State.IDLE -> R.string.now_batting
            ClipPlayback.State.LOADING -> R.string.loading
            ClipPlayback.State.PLAYING -> R.string.playing
            ClipPlayback.State.PAUSED -> R.string.paused
        })
        if (state == ClipPlayback.State.IDLE || state == ClipPlayback.State.LOADING) {
            findViewById<LinearProgressIndicator>(R.id.playbackProgress).progress = 0
            val player = selectedPlayer
            findViewById<TextView>(R.id.textViewClipTime).text = if (player?.songPath != null) {
                getString(R.string.clip_time, time(player.startTime), time(player.endTime), player.endTime - player.startTime)
            } else getString(R.string.no_clip)
        }
        renderPreviewButton()
    }

    private fun renderPreviewButton() {
        val dialog = editor ?: return
        if (editorKind != "clip") return
        val button = dialog.findViewById<MaterialButton>(R.id.buttonPreviewClip) ?: return
        val active = playback.state != ClipPlayback.State.IDLE
        button.setText(if (active) R.string.stop_preview else R.string.preview_clip)
        button.setIconResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
    }

    private fun renderProgress(elapsed: Int, duration: Int) {
        findViewById<LinearProgressIndicator>(R.id.playbackProgress).progress =
            (elapsed.toLong() * 1000 / duration).toInt()
        findViewById<TextView>(R.id.textViewClipTime).text =
            getString(R.string.playback_time, time(elapsed / 1000), time(duration / 1000))
    }

    private fun time(seconds: Int) = String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)

    private fun showPlayerEditor(player: Player? = null, draft: Bundle? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_player, null)
        val name = view.findViewById<EditText>(R.id.editTextPlayerName)
        val number = view.findViewById<EditText>(R.id.editTextPlayerNumber)
        name.setText(draft?.getString("name") ?: player?.name.orEmpty())
        number.setText(draft?.getString("number") ?: player?.number?.toString().orEmpty())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (player == null) R.string.add_player else R.string.edit_player)
            .setView(view).setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null).create()
        trackEditor(dialog, "player", player)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val jersey = number.text.toString().toIntOrNull()
            if (name.text.isNullOrBlank() || jersey == null || jersey !in 0..999) {
                name.error = getString(R.string.invalid_player)
            } else {
                if (player == null) {
                    val added = Player(name.text.toString().trim(), jersey)
                    players.add(added)
                    if (selectedPlayer == null) selectedPlayer = added
                } else {
                    player.name = name.text.toString().trim()
                    player.number = jersey
                }
                savePlayers()
                refresh()
                dialog.dismiss()
            }
        }
    }

    private fun showPlayerMenu(player: Player, anchor: View) {
        PopupMenu(this, anchor).apply {
            val index = players.indexOf(player)
            menu.add(0, R.string.edit_player, 0, R.string.edit_player)
            menu.add(0, R.string.choose_song, 1, R.string.choose_song)
            menu.add(0, R.string.edit_clip, 2, R.string.edit_clip).isEnabled = player.songPath != null
            menu.add(0, R.string.move_up, 3, R.string.move_up).isEnabled = index > 0
            menu.add(0, R.string.move_down, 4, R.string.move_down).isEnabled = index < players.lastIndex
            menu.add(0, R.string.remove_player, 5, R.string.remove_player)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.string.edit_player -> showPlayerEditor(player)
                    R.string.choose_song -> chooseFile(player)
                    R.string.edit_clip -> player.songPath?.let {
                        showClipEditor(player, Uri.parse(it), autoTranscribe = player.words.isNullOrEmpty())
                    }
                    R.string.move_up, R.string.move_down -> {
                        val from = players.indexOf(player)
                        val to = from + if (item.itemId == R.string.move_up) -1 else 1
                        players.add(to, players.removeAt(from))
                        savePlayers()
                        refresh()
                    }
                    R.string.remove_player -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.remove_player)
                        .setMessage(getString(R.string.remove_confirmation, player.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.remove_player) { _, _ ->
                            if (selectedPlayer === player) playback.stop()
                            val position = players.indexOf(player)
                            players.remove(player)
                            if (selectedPlayer === player) selectedPlayer = players.getOrNull(position) ?: players.firstOrNull()
                            savePlayers()
                            refresh()
                        }.show()
                }
                true
            }
            show()
        }
    }

    private fun chooseFile(player: Player) {
        playback.stop()
        pendingFilePlayerId = player.id
        try {
            selectFileLauncher.launch(arrayOf("audio/*"))
        } catch (_: ActivityNotFoundException) {
            pendingFilePlayerId = null
            message(R.string.file_error)
        }
    }

    private fun showClipEditor(player: Player, uri: Uri, draft: Bundle? = null, autoTranscribe: Boolean = false) {
        if (selectedPlayer === player) playback.stop()
        if (draft == null) anchorWordIndex = null
        val view = layoutInflater.inflate(R.layout.dialog_time_selection, null)
        val start = view.findViewById<EditText>(R.id.editTextStartTime)
        val end = view.findViewById<EditText>(R.id.editTextEndTime)
        val preview = view.findViewById<MaterialButton>(R.id.buttonPreviewClip)
        val sameFile = player.songPath == uri.toString()
        start.setText(draft?.getString("start") ?: if (sameFile) player.startTime.toString() else "0")
        end.setText(draft?.getString("end") ?: if (sameFile) player.endTime.toString() else "30")
        preview.setOnClickListener {
            if (playback.state != ClipPlayback.State.IDLE) {
                playback.stop()
            } else {
                val from = start.text.toString().toIntOrNull()
                val to = end.text.toString().toIntOrNull()
                if (from == null || to == null || from < 0 || to <= from || to > Int.MAX_VALUE / 1000) {
                    end.error = getString(R.string.invalid_clip)
                } else {
                    val temp = player.copy(songPath = uri.toString(), startTime = from, endTime = to)
                    playback.play(temp, advanceOnComplete = false)
                }
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clip_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.transcribe_song, null)
            .create()
        trackEditor(dialog, "clip", player, uri.toString())
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            requestSongTranscription(player, uri)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val from = start.text.toString().toIntOrNull()
            val to = end.text.toString().toIntOrNull()
            if (from == null || to == null || from < 0 || to <= from || to > Int.MAX_VALUE / 1000) {
                end.error = getString(R.string.invalid_clip)
            } else {
                player.songPath = uri.toString()
                player.songTitle = fileName(uri)
                player.startTime = from
                player.endTime = to
                anchorWordIndex = null
                savePlayers()
                refresh()
                dialog.dismiss()
            }
        }
        if (autoTranscribe && groqApiKey().isNotBlank() && !SongTranscriber.isBusy) {
            val targetSnapshot = player.copy(songPath = uri.toString(), songTitle = fileName(uri))
            SongTranscriber.start(this, targetSnapshot, groqApiKey())
        }
        renderClipDialog(player)
        renderPreviewButton()
    }

    private fun renderClipDialog(player: Player) {
        val dialog = editor ?: return
        if (editorKind != "clip") return
        val prompt = dialog.findViewById<TextView>(R.id.textViewClipPrompt) ?: return
        val progress = dialog.findViewById<View>(R.id.transcribeProgress) ?: return
        val chipGroup = dialog.findViewById<ChipGroup>(R.id.chipGroupWords) ?: return
        val startInput = dialog.findViewById<EditText>(R.id.editTextStartTime) ?: return
        val endInput = dialog.findViewById<EditText>(R.id.editTextEndTime) ?: return
        val transcribeButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        val transcribing = SongTranscriber.isBusy
        progress.isVisible = transcribing
        transcribeButton?.isEnabled = !transcribing
        transcribeButton?.setText(if (transcribing) R.string.transcribing_song else R.string.transcribe_song)

        val words = player.words.orEmpty()
        chipGroup.removeAllViews()
        if (words.isEmpty()) {
            chipGroup.isVisible = false
            prompt.setText(if (transcribing) R.string.transcribing_song else R.string.transcript_hint)
            return
        }
        chipGroup.isVisible = true
        val currentStart = startInput.text.toString().toIntOrNull() ?: player.startTime
        val currentEnd = endInput.text.toString().toIntOrNull() ?: player.endTime
        val anchor = anchorWordIndex?.let { words.getOrNull(it) }
        val inClipWords = words.filter { it.end > currentStart && it.start < currentEnd }
        val excerpt = inClipWords.ifEmpty { words.take(6) }.joinToString(" ") { it.word }
        prompt.text = when {
            transcribing -> getString(R.string.transcribing_song)
            anchor != null -> getString(R.string.word_clip_tap_end, time(floor(anchor.start).toInt()), anchor.word)
            else -> getString(R.string.word_clip_summary, time(currentStart), time(currentEnd), excerpt)
        }
        words.forEachIndexed { index, timedWord ->
            val inClip = timedWord.end > currentStart && timedWord.start < currentEnd
            val chip = Chip(this).apply {
                text = getString(R.string.word_chip_label, time(floor(timedWord.start).toInt()), timedWord.word)
                isCheckable = true
                isChecked = inClip
                isCheckedIconVisible = false
                setOnClickListener {
                    onDialogWordTapped(player, index, startInput, endInput)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun onDialogWordTapped(player: Player, index: Int, startInput: EditText, endInput: EditText) {
        val words = player.words ?: return
        val tapped = words.getOrNull(index) ?: return
        playback.stop()
        val currentAnchor = anchorWordIndex?.let { words.getOrNull(it) }
        if (currentAnchor == null) {
            anchorWordIndex = index
            val start = floor(tapped.start).toInt().coerceAtLeast(0)
            val end = maxOf(start + 1, ceil(tapped.end).toInt())
            startInput.setText(start.toString())
            endInput.setText(end.toString())
        } else {
            anchorWordIndex = null
            val start = floor(minOf(currentAnchor.start, tapped.start)).toInt().coerceAtLeast(0)
            val end = maxOf(start + 1, ceil(maxOf(currentAnchor.end, tapped.end)).toInt())
            startInput.setText(start.toString())
            endInput.setText(end.toString())
        }
        renderClipDialog(player)
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: getString(R.string.local_audio)
            }
        } catch (_: RuntimeException) {
            // The playback path reports unreadable or revoked documents without losing the lineup.
        }
        return getString(R.string.local_audio)
    }

    private fun groqApiKey(): String =
        preferences.getString("groq_api_key", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.GROQ_API_KEY.trim()

    private fun requestSongTranscription(player: Player, uri: Uri) {
        if (groqApiKey().isBlank()) {
            editor?.dismiss()
            showGroqKeyEditor(player, uri)
        } else {
            playback.stop()
            val snapshot = player.copy(songPath = uri.toString(), songTitle = fileName(uri))
            SongTranscriber.start(this, snapshot, groqApiKey())
        }
    }

    private fun showGroqKeyEditor(player: Player? = null, uri: Uri? = null, draftKey: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_groq_key, null)
        val input = view.findViewById<EditText>(R.id.editTextGroqKey)
        input.setText(draftKey ?: preferences.getString("groq_api_key", null).orEmpty())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.groq_key_title)
            .setMessage(R.string.groq_key_prompt)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        val effectiveUri = uri ?: player?.songPath?.let(Uri::parse)
        trackEditor(dialog, "groq_key", player, effectiveUri?.toString())
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                input.error = getString(R.string.invalid_groq_key)
            } else {
                preferences.edit().putString("groq_api_key", key).apply()
                dialog.dismiss()
                if (player != null && effectiveUri != null) {
                    showClipEditor(player, effectiveUri, autoTranscribe = true)
                }
            }
        }
    }

    private fun handleSongTranscription(playerId: String, outcome: SongTranscriber.Outcome) {
        val target = players.find { it.id == playerId }
        when (outcome) {
            is SongTranscriber.Outcome.Success -> if (target != null) {
                target.words = outcome.words
                anchorWordIndex = null
                savePlayers()
                if (editorKind == "clip" && editorPlayerId == target.id) {
                    renderClipDialog(target)
                } else {
                    val uri = (editorUri ?: target.songPath)?.let(Uri::parse)
                    if (uri != null) showClipEditor(target, uri, autoTranscribe = false)
                }
            }
            is SongTranscriber.Outcome.Failure -> {
                message(outcome.messageRes)
                if (outcome.authFailure && target != null) {
                    val uri = (editorUri ?: target.songPath)?.let(Uri::parse)
                    editor?.dismiss()
                    showGroqKeyEditor(target, uri)
                } else if (target != null && editorKind == "clip") {
                    renderClipDialog(target)
                }
            }
        }
    }

    private fun trackEditor(dialog: AlertDialog, kind: String, player: Player?, uri: String? = null) {
        editor = dialog
        editorKind = kind
        editorPlayerId = player?.id
        editorUri = uri
        dialog.setOnDismissListener {
            if (kind == "clip") playback.stop()
            if (editor === dialog) {
                editor = null
                editorKind = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingFile", pendingFilePlayerId)
        outState.putInt("anchorWord", anchorWordIndex ?: -1)
        outState.putString("editorKind", editorKind)
        outState.putString("editorPlayer", editorPlayerId)
        outState.putString("editorUri", editorUri)
        listOf("name" to R.id.editTextPlayerName, "number" to R.id.editTextPlayerNumber,
            "start" to R.id.editTextStartTime, "end" to R.id.editTextEndTime,
            "groqKey" to R.id.editTextGroqKey).forEach { (key, id) ->
            editor?.findViewById<EditText>(id)?.let { outState.putString(key, it.text.toString()) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_settings) return super.onOptionsItemSelected(item)
        startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        playback.stop()
        unregisterReceiver(noisyReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        SongTranscriber.detach()
        editor?.dismiss()
        playback.stop()
        super.onDestroy()
    }

    private fun message(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()
}
