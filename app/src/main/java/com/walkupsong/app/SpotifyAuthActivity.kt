package com.walkupsong.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.spotify.sdk.android.authentication.AuthenticationClient
import com.spotify.sdk.android.authentication.AuthenticationRequest
import com.spotify.sdk.android.authentication.AuthenticationResponse

class SpotifyAuthActivity : AppCompatActivity() {

    private lateinit var CLIENT_ID: String
    private val REDIRECT_URI = "com.walkupsong.app://callback"
    private val REQUEST_CODE = 1337

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        CLIENT_ID = sharedPreferences.getString("spotify_client_id", "") ?: ""

        if (CLIENT_ID.isEmpty()) {
            Toast.makeText(this, "Please enter your Spotify Client ID in the settings", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val builder = AuthenticationRequest.Builder(CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI)
        builder.setScopes(arrayOf("streaming"))
        val request = builder.build()

        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            val response = AuthenticationClient.getResponse(resultCode, data)
            when (response.type) {
                AuthenticationResponse.Type.TOKEN -> {
                    // Store the access token
                    val accessToken = response.accessToken
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    sharedPreferences.edit().putString("spotify_access_token", accessToken).apply()
                }
                AuthenticationResponse.Type.ERROR -> {
                    // Handle error
                }
                else -> {
                    // Handle other cases
                }
            }
        }
        finish()
    }
}
