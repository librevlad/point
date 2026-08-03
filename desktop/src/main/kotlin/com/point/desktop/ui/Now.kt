package com.point.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * «Сейчас», которое само не отстаёт.
 *
 * Журнал (#407) говорит «сегодня 23:50» — и это верно ровно до полуночи. Point на компьютере живёт
 * сутками, поэтому время пересчитывается раз в минуту: замерший ответ соврал бы именно в тот
 * момент, когда человек на него смотрит (тот же урок, что у полосы связи в #412).
 */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
