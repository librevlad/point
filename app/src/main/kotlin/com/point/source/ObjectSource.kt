package com.point.source

import android.content.Context
import android.content.Intent

interface ObjectSource {

    val id: String

    val label: String

    /**
     * Что человек получит, если сюда войти (#568).
     *
     * Разрешения просились молча: тап по «Месту» сразу поднимал системное окно, и первый
     * разговор о доверии шёл от имени Android, а не Point. Слова — про пользу, а не про само
     * разрешение: «записать голос и расшифровать», а не «нужен доступ к микрофону».
     */
    val what: String? get() = null

    val icon: String get() = "open-in"

    fun isAvailable(context: Context): Boolean

    val permissions: List<String> get() = emptyList()

    suspend fun request(context: Context): Intent?

    suspend fun read(context: Context, data: Intent?): Produced?

    fun saveState(): String? = null

    fun restoreState(state: String?) = Unit
}
