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
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        // Шаринг текста, в котором текста нет (пробелы либо вовсе без EXTRA_TEXT), — тот же
        // пустой вход, что и в меню выделения: слово человеку, без объекта и экрана (#1096).
        val textShare = intent.action == Intent.ACTION_SEND && stream == null &&
            (text != null || intent.type?.startsWith("text/") == true)
        if (textShare && text.isNullOrBlank()) {
            refuseEmptySelection()
            return
        }

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

            null -> viewModel.refuseIncoming()
        }
    }
}
