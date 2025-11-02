package com.walkupsong.app

data class Track(
    val title: String,
    val previewUrl: String
)

interface MusicService {
    fun searchTracks(query: String, callback: (List<Track>) -> Unit)
}
