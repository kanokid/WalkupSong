package com.walkupsong.app

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface DeezerApiService {
    @GET("search")
    fun searchTracks(@Query("q") query: String): Call<DeezerResponse>
}
