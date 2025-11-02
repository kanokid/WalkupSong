package com.walkupsong.app

data class Player(
    val name: String,
    val number: Int,
    var songPath: String? = null,
    var songTitle: String? = null,
    var isSelected: Boolean = false,
    var startTime: Int = 0,
    var endTime: Int = 0
)
