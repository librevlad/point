package com.point

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * Дверь «Поделиться» и «Открыть с помощью» (#249): принимает системный Share и открытие файла из
 * любого приложения. `content://` стрингуется здесь, на границе, — ниже `:app` никто не знает про
 * Android Uri. Экран, «назад» и уборка scratch общие для всех дверей и живут в [FlowHostActivity].
 */
@AndroidEntryPoint
class ShareActivity : FlowHostActivity() {

    override val restoresJourney: Boolean get() = true

    override fun accept(intent: Intent) {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when (
            val incoming = incomingOf(
                action = intent.action,
                // При «Открыть с помощью» intent часто без типа: спрашиваем систему, иначе
                // объект приедет как «неизвестно что» и потеряет половину действий.
                type = intent.type ?: intent.data?.let { contentResolver.getType(it) },
                data = intent.data?.toString(),
                stream = stream?.toString(),
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                streams = streams?.map { it.toString() }.orEmpty(),
            )
        ) {
            is Incoming.Single -> viewModel.onShared(incoming.uri, incoming.mime)
            is Incoming.Many -> viewModel.onSharedMultiple(incoming.uris)
            is Incoming.Body -> viewModel.onSharedText(incoming.text)
            // Разобрать не вышло — и раньше здесь не происходило ровно ничего: человек видел
            // пустой чёрный экран без единого слова и без выхода. Молчание в ответ на действие —
            // худший из отказов: непонятно даже, дошло ли оно.
            null -> viewModel.refuseIncoming()
        }
    }
}
