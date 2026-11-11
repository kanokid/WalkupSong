package com.walkupsong.app

import java.util.UUID

data class TimedWord(
    val word: String,
    val start: Double,
    val end: Double
)

data class Player(
    var name: String,
    var number: Int,
    var songPath: String? = null,
    var songTitle: String? = null,
    @Transient var isSelected: Boolean = false,
    var startTime: Int = 0,
    var endTime: Int = 0,
    var id: String? = UUID.randomUUID().toString(),
    var words: List<TimedWord>? = null
)
