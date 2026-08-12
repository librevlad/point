package com.point.source

import android.content.Context
import android.content.Intent
import com.point.core.flow.NO_INTERNET_NOTE

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

    /**
     * Источник выйдет в сеть (#759).
     *
     * Дверь остаётся видимой и нажимаемой — прятать её нельзя, — но пока сети нет, она
     * говорит об этом до тапа, как и действия над объектом (#569). Прежде «Принять файл»
     * выглядел обычной строкой и отказывал уже после нажатия.
     */
    val network: Boolean get() = false

    /**
     * Источнику нужен аккаунт (#897).
     *
     * «Принять файл по ссылке» без входа отказывал уже ПОСЛЕ тапа — экраном с одной красной
     * плашкой. Причина должна стоять до тапа, ровно как у сети.
     */
    val account: Boolean get() = false

    fun isAvailable(context: Context): Boolean

    val permissions: List<String> get() = emptyList()

    suspend fun request(context: Context): Intent?

    suspend fun read(context: Context, data: Intent?): Produced?

    fun saveState(): String? = null

    fun restoreState(state: String?) = Unit
}

/**
 * Подпись источника в списке (#759).
 *
 * Пока сети нет, сетевой источник говорит об этом вместо обещания пользы — ровно так же,
 * как действие над объектом называет причину вместо своего обещания. Строка остаётся
 * нажимаемой: прятать дверь, ради которой человек сюда пришёл, нельзя.
 */
fun sourceNote(source: ObjectSource, online: Boolean, signedIn: Boolean = true): String? = when {
    source.network && !online -> NO_INTERNET_NOTE
    source.account && !signedIn -> com.point.core.flow.NOT_IN_ACCOUNT_NOTE
    else -> source.what
}
