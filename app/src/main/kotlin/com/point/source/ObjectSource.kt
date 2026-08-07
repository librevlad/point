package com.point.source

import android.content.Context
import android.content.Intent

interface ObjectSource {

    val id: String

    val label: String

    val icon: String get() = "open-in"

    fun isAvailable(context: Context): Boolean

    val permissions: List<String> get() = emptyList()

    suspend fun request(context: Context): Intent?

    suspend fun read(context: Context, data: Intent?): Produced?

    fun saveState(): String? = null

    fun restoreState(state: String?) = Unit
}
