package com.walkupsong.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerViewPlayers: RecyclerView
    private lateinit var playerAdapter: PlayerAdapter
    private var players: MutableList<Player> = mutableListOf()
    private var mediaPlayer: MediaPlayer? = null
    private var selectedPlayer: Player? = null
    private var currentBatterIndex = -1
    private val handler = Handler(Looper.getMainLooper())

    private val STORAGE_PERMISSION_CODE = 1

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showTimeSelectionDialog(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        loadPlayers()
        requestStoragePermission()

        recyclerViewPlayers = findViewById(R.id.recyclerViewBattingOrder)
        val editTextPlayerName = findViewById<TextInputEditText>(R.id.editTextPlayerName)
        val editTextPlayerNumber = findViewById<TextInputEditText>(R.id.editTextPlayerNumber)
        val buttonAddPlayer = findViewById<Button>(R.id.buttonAddPlayer)
        val buttonSelectFile = findViewById<Button>(R.id.buttonSelectFile)
        val buttonPlayStop = findViewById<Button>(R.id.buttonPlayStop)

        buttonPlayStop.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopSong()
            } else {
                if (players.isNotEmpty()) {
                    if (currentBatterIndex == -1) {
                        currentBatterIndex = 0
                    }
                    selectedPlayer = players[currentBatterIndex]
                    playerAdapter.notifyDataSetChanged()
                    selectedPlayer?.songPath?.let { playSong(it) }
                }
            }
        }

        buttonAddPlayer.setOnClickListener {
            val name = editTextPlayerName.text.toString()
            val number = editTextPlayerNumber.text.toString().toIntOrNull()
            if (name.isNotBlank() && number != null) {
                val player = Player(name, number)
                playerAdapter.addPlayer(player)
                editTextPlayerName.text?.clear()
                editTextPlayerNumber.text?.clear()
                savePlayers()
            } else {
                Toast.makeText(this, "Please enter a valid name and number", Toast.LENGTH_SHORT).show()
            }
        }

        buttonSelectFile.setOnClickListener {
            if (selectedPlayer == null) {
                Toast.makeText(this, "Please select a player first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectFileLauncher.launch(arrayOf("audio/*"))
        }

        setupRecyclerView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        playerAdapter = PlayerAdapter(players) { player ->
            players.forEach { it.isSelected = false }
            player.isSelected = true
            selectedPlayer = player
            currentBatterIndex = players.indexOf(player)
            playerAdapter.notifyDataSetChanged()
        }
        recyclerViewPlayers.layoutManager = LinearLayoutManager(this)
        recyclerViewPlayers.adapter = playerAdapter
    }

    private fun playSong(path: String) {
        mediaPlayer?.release()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, Uri.parse(path))
            prepareAsync()
            setOnPreparedListener {
                val startTime = selectedPlayer?.startTime ?: 0
                val endTime = selectedPlayer?.endTime ?: 0
                seekTo(startTime * 1000)
                start()
                val buttonPlayStop = findViewById<Button>(R.id.buttonPlayStop)
                buttonPlayStop.text = "Stop"
                if (endTime > 0) {
                    handler.postDelayed({
                        stopSong()
                    }, ((endTime - startTime) * 1000).toLong())
                }
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Error playing song.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun stopSong() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        val buttonPlayStop = findViewById<Button>(R.id.buttonPlayStop)
        buttonPlayStop.text = "Play"

        if (players.isNotEmpty()) {
            currentBatterIndex++
            if (currentBatterIndex >= players.size) {
                currentBatterIndex = 0
            }
            players.forEach { it.isSelected = false }
            selectedPlayer = players[currentBatterIndex]
            selectedPlayer?.isSelected = true
            playerAdapter.notifyDataSetChanged()
        }
    }

    private fun savePlayers() {
        val sharedPreferences = getSharedPreferences("walkup-song", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()
        val json = gson.toJson(players)
        editor.putString("players", json)
        editor.apply()
    }

    private fun loadPlayers() {
        val sharedPreferences = getSharedPreferences("walkup-song", Context.MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPreferences.getString("players", null)
        val type = object : TypeToken<MutableList<Player>>() {}.type
        players = gson.fromJson(json, type) ?: mutableListOf()
        if (players.isNotEmpty()) {
            currentBatterIndex = 0
            selectedPlayer = players[0]
            players[0].isSelected = true
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied. App may not function correctly.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTimeSelectionDialog(uri: Uri) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_time_selection, null)
        val editTextStartTime = dialogView.findViewById<EditText>(R.id.editTextStartTime)
        val editTextEndTime = dialogView.findViewById<EditText>(R.id.editTextEndTime)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Start and End Time")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener {
                val startTime = editTextStartTime.text.toString().toIntOrNull() ?: 0
                val endTime = editTextEndTime.text.toString().toIntOrNull() ?: 0

                if (endTime > startTime) {
                    selectedPlayer?.songPath = uri.toString()
                    selectedPlayer?.songTitle = "Local File"
                    selectedPlayer?.startTime = startTime
                    selectedPlayer?.endTime = endTime
                    val index = players.indexOf(selectedPlayer)
                    if (index != -1) {
                        playerAdapter.notifyItemChanged(index)
                    }
                    savePlayers()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "End time must be greater than start time", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        handler.removeCallbacksAndMessages(null)
    }
}
