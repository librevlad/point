package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechToText
import com.point.core.flow.TRANSCRIBE_PROMPT
import com.point.core.flow.Transcription
import com.point.core.flow.modelReadableAudio
import com.point.core.flow.parseTranscription
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class LlmSpeechToText @Inject constructor(
    private val llm: LlmClient,
) : SpeechToText {

    override fun missingKey(): SpeechKeyNeed? =
        if (llm.configured) null else SpeechKeyNeed("модель общего назначения — по любому ключу AI")

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {

        if (modelReadableAudio(obj.mime, obj.metadata["name"]) == null) {
            error("Этот формат записи модель не читает — подойдут ogg, mp3, m4a, wav, flac")
        }

        val bytes = File(obj.uri.value).length()
        if (bytes > MAX_INLINE_BYTES) {
            error("Запись слишком большая (${bytes / (1024 * 1024)} МБ) — модель принимает до 15 МБ")
        }
        val answer = llm.run(obj, TRANSCRIBE_PROMPT)
        val raw = runCatching { File(answer.uri.value).readText() }
            .getOrElse { error("Ответ модели не прочитался: ${it.message}") }
        parseTranscription(raw)
    }
}
