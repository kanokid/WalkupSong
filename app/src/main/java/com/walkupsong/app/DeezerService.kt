package com.walkupsong.app

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DeezerService : MusicService {
    override fun searchTracks(query: String, callback: (List<Track>) -> Unit) {
        RetrofitClient.instance.searchTracks(query).enqueue(object : Callback<DeezerResponse> {
            override fun onResponse(call: Call<DeezerResponse>, response: Response<DeezerResponse>) {
                if (response.isSuccessful) {
                    val tracks = response.body()?.data?.map {
                        Track(it.title, it.preview)
                    } ?: emptyList()
                    callback(tracks)
                } else {
                    callback(emptyList())
                }
            }

            override fun onFailure(call: Call<DeezerResponse>, t: Throwable) {
                callback(emptyList())
            }
        })
    }
}
