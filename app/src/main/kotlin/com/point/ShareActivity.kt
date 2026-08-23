package com.point

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.point.source.EXTRA_OBJECT_NAME
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareActivity : FlowHostActivity() {

    override val restoresJourney: Boolean get() = true

    override fun accept(intent: Intent) {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

        // Текст приезжает не только строкой: отправитель вправе положить размеченный
        // CharSequence, и getStringExtra отдал бы на нём null — настоящий текст выглядел бы
        // пустым входом (#1096).
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)

        when (
            val incoming = incomingOf(
                action = intent.action,

                type = intent.type ?: intent.data?.let { contentResolver.getType(it) },
                data = intent.data?.toString(),
                stream = stream?.toString(),
                text = text,
                streams = streams?.map { it.toString() }.orEmpty(),
            )
        ) {

            is Incoming.Single ->
                viewModel.onShared(incoming.uri, incoming.mime, name = intent.getStringExtra(EXTRA_OBJECT_NAME))
            is Incoming.Many -> viewModel.onSharedMultiple(incoming.uris)
            is Incoming.Body -> viewModel.onSharedText(incoming.text)

            // Пришли текстом, а текста нет (пробелы либо вовсе без EXTRA_TEXT) — тот же пустой
            // вход, что и пустое выделение в меню: слово человеку, без объекта (#1096). Всё
            // прочее непонятое остаётся отказом на своих словах.
            null -> if (textDoor(intent, stream, text)) refuseEmptySelection() else viewModel.refuseIncoming()
        }
    }

    private fun textDoor(intent: Intent, stream: Uri?, text: CharSequence?): Boolean =
        intent.action == Intent.ACTION_SEND && stream == null &&
            (text != null || intent.type?.startsWith("text/") == true)
}
