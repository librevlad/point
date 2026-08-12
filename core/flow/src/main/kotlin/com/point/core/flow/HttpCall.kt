package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

/**
 * Запрос, который заканчивается вместе с отказом человека.
 *
 * `HttpURLConnection` держит поток и об отмене шага не знает: отменённый запрос иначе досиживал
 * свой таймаут в тишине и тратил бесплатный лимит уже после того, как человек отказался (#692).
 * Поэтому отказ закрывает соединение сам.
 */
suspend fun <T> HttpURLConnection.callClosingOnCancel(call: HttpURLConnection.() -> T): T =
    coroutineScope {
        val step = coroutineContext[Job]
        val closeOnCancel = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                if (step?.isCancelled == true) runCatching { disconnect() }
            }
        }
        try {
            withContext(Dispatchers.IO) { call() }
        } finally {
            closeOnCancel.cancel()
        }
    }
