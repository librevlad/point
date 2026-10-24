package com.point.data

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Модель ML Kit ждётся не дольше [ms].
 *
 * Без сети её загрузка не падает, а ждёт сеть сколько угодно (замер #1044 24.10.2026: эмулятор
 * в режиме полёта после стирания данных). Поиск значений ждал её вслед за чтением, и экран
 * оставался на «Распознаю текст…» минутами, хотя текст был уже прочитан. Неготовая модель —
 * сбой этой попытки, а не «ничего нет»: дальше идут те, кто модели не ждёт. Загрузка при этом
 * не отменяется и с появлением сети доходит до конца сама.
 */
internal suspend fun Task<Void>.awaitModel(ms: Long = MODEL_WAIT_MS) {
    withTimeoutOrNull(ms) { await(); true } ?: error(MODEL_NOT_READY)
}

internal const val MODEL_NOT_READY = "on-device model not downloaded"

private const val MODEL_WAIT_MS = 20_000L
