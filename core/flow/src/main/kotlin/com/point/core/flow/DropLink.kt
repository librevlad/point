package com.point.core.flow

fun interface DropLink {

    suspend fun give(path: String, fileName: String, mime: String): String?
}
