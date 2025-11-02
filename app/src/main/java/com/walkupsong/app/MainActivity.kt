package com.walkupsong.app

import android.content.Context
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var searchViewSong: SearchView
    private lateinit var recyclerViewPlayers: RecyclerView
    private lateinit var playerAdapter: PlayerAdapter
    private var players: MutableList<Player> = mutableListOf()
    private var mediaPlayer: MediaPlayer? = null
    private var selectedPlayer: Player? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadPlayers()

        searchViewSong = findViewById(R.id.searchViewSong)
        recyclerViewPlayers = findViewById(R.id.recyclerViewBattingOrder)
        val editTextPlayerName = findViewById<TextInputEditText>(R.id.editTextPlayerName)
        val editTextPlayerNumber = findViewById<TextInputEditText>(R.id.editTextPlayerNumber)
        val buttonAddPlayer = findViewById<Button>(R.id.buttonAddPlayer)
        val buttonPlay = findViewById<Button>(R.id.buttonPlay)
        val buttonPause = findViewById<Button>(R.id.buttonPause)
        val buttonStop = findViewById<Button>(R.id.buttonStop)

        buttonPlay.setOnClickListener {
            selectedPlayer?.songUri?.let { playSong(it) }
        }
        buttonPause.setOnClickListener { pauseSong() }
        buttonStop.setOnClickListener { stopSong() }

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

        setupRecyclerView()
        setupSearchView()
    }

    private fun setupRecyclerView() {
        playerAdapter = PlayerAdapter(players) { player ->
            selectedPlayer = player
            Toast.makeText(this, "Selected: ${player.name}", Toast.LENGTH_SHORT).show()
        }
        recyclerViewPlayers.layoutManager = LinearLayoutManager(this)
        recyclerViewPlayers.adapter = playerAdapter
    }

    private fun setupSearchView() {
        searchViewSong.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (selectedPlayer == null) {
                    Toast.makeText(this@MainActivity, "Please select a player first", Toast.LENGTH_SHORT).show()
                    return false
                }
                query?.let {
                    searchTracks(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
    }

    private fun searchTracks(query: String) {
        RetrofitClient.instance.searchTracks(query).enqueue(object : Callback<DeezerResponse> {
            override fun onResponse(call: Call<DeezerResponse>, response: Response<DeezerResponse>) {
                if (response.isSuccessful) {
                    response.body()?.data?.let {
                        showSongSelectionDialog(it)
                    }
                } else {
                    Log.e("API_ERROR", "Failed to fetch songs: ${response.code()}")
                    Toast.makeText(this@MainActivity, "Failed to fetch songs. Please try again later.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<DeezerResponse>, t: Throwable) {
                Log.e("API_FAILURE", "Failed to fetch songs", t)
                Toast.makeText(this@MainActivity, "An error occurred. Please check your internet connection.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showSongSelectionDialog(songs: List<Song>) {
        val songTitles = songs.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select a Song")
            .setItems(songTitles) { _, which ->
                val selectedSong = songs[which]
                selectedPlayer?.songUri = selectedSong.preview
                selectedPlayer?.songTitle = selectedSong.title
                val index = players.indexOf(selectedPlayer)
                if (index != -1) {
                    playerAdapter.notifyItemChanged(index)
                }
                savePlayers()
            }
            .show()
    }

    private fun playSong(url: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            prepareAsync()
            setOnPreparedListener {
                start()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Error playing song.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun pauseSong() {
        mediaPlayer?.pause()
    }

    private fun stopSong() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
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
    }


    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
