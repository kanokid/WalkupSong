package com.walkupsong.app

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.spotify.sdk.android.auth.AuthorizationHandler
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface SpotifyApiService {
    @GET("v1/search")
    fun searchTracks(@Header("Authorization") accessToken: String, @Query("q") query: String, @Query("type") type: String = "track"): Call<SpotifySearchResponse>
}

data class SpotifySearchResponse(
    val tracks: SpotifyTracks
)

data class SpotifyTracks(
    val items: List<SpotifyTrack>
)

data class SpotifyTrack(
    val name: String,
    val preview_url: String?
)

class SpotifyService(private val context: Context) : MusicService {
    private val spotifyApi: SpotifyApiService

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        spotifyApi = retrofit.create(SpotifyApiService::class.java)
    }

    override fun searchTracks(query: String, callback: (List<Track>) -> Unit) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val accessToken = sharedPreferences.getString("spotify_access_token", null)

        if (accessToken == null) {
            // Not authenticated, launch the auth flow
            val intent = Intent(context, SpotifyAuthActivity::class.java)
            context.startActivity(intent)
            return
        }

        spotifyApi.searchTracks("Bearer $accessToken", query).enqueue(object : Callback<SpotifySearchResponse> {
            override fun onResponse(call: Call<SpotifySearchResponse>, response: Response<SpotifySearchResponse>) {
                if (response.isSuccessful) {
                    val tracks = response.body()?.tracks?.items
                        ?.filter { it.preview_url != null }
                        ?.map {
                            Track(it.name, it.preview_url!!)
                        } ?: emptyList()
                    callback(tracks)
                } else {
                    callback(emptyList())
                }
            }

            override fun onFailure(call: Call<SpotifySearchResponse>, t: Throwable) {
                callback(emptyList())
            }
        })
    }
}
