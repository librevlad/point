package com.point.data

import com.point.core.flow.LlmClient
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

/**
 * Расшифровка через модель, принимающую аудио вложением (#223) — первая (и пока единственная)
 * реализация [SpeechToText].
 *
 * Почему облако, а не устройство: системного распознавания **из файла** в Android нет.
 * `SpeechRecognizer`, включая `createOnDeviceSpeechRecognizer`, слушает микрофон; подать ему
 * готовый `ogg` публичным API нельзя. Обоснование целиком — в `docs/DECISIONS.md` (#223).
 *
 * Клиент берётся общий ([LlmClient] — цепочка провайдеров), поэтому ротация ключей, сводка
 * ошибок и «нет сети» приходят сюда даром, а маршрутизацию «кто из провайдеров слышит» держит
 * `canHandle` каждого клиента.
 */
class LlmSpeechToText @Inject constructor(
    private val llm: LlmClient,
) : SpeechToText {

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {
        // Отказ до сети и до траты квоты: формат, которого не читает никто, честнее назвать
        // словами здесь, чем получить HTTP 400 и показать человеку кусок чужого JSON.
        if (modelReadableAudio(obj.mime, obj.metadata["name"]) == null) {
            error("Этот формат записи модель не читает — подойдут ogg, mp3, m4a, wav, flac")
        }
        // Слишком тяжёлая запись не влезает во вложение, и без него модель ответила бы про
        // запись, которой не слышала. Сказать это до сети — честнее, чем после.
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
