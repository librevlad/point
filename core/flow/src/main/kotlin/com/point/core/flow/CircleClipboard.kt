package com.point.core.flow

fun interface CircleClipboard {

    suspend fun offer(text: String)

    companion object {

        val None = CircleClipboard { }
    }
}
