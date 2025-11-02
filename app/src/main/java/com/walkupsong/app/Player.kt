package com.walkupsong.app

data class Player(
    val name: String,
    val number: Int,
    var songUri: String? = null,
    var songTitle: String? = null,
    var isSelected: Boolean = false
)
