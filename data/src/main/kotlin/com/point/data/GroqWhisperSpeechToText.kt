package com.point.data

import com.point.core.flow.FirstHeardSpeechToText
import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.KEY_SETTINGS_CALL
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.modelReadableAudio
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Whisper на Groq — **первый движок расшифровки** (#223).
 *
 * Первый не по вкусу, а по замеру 04.08.2026 на записях владельца. Модель общего назначения даёт
 * бесплатно 20 запросов в СУТКИ (`HTTP 429, generate_content_free_tier_requests, limit: 20`) — за
 * вечер квота кончилась, и расшифровки не стало. Whisper (`whisper-large-v3-turbo`) на трёх записях
 * с эталоном прочитал украинскую речь дословно и даром: «А може до якого ґазди?» — слово в слово, в
 * двух других расхождения косметические («той ходім» / «то й ходім», место апострофа в
 * «Д'Артаньян»). Формальная ошибка слов 9,5 %, по сути — ноль.
 *
 * **Отдельная ручка, а не «модель, которой показали файл».** `POST /audio/transcriptions` — это
 * распознавание речи, у него нет промпта, нет формата ответа и нечему сломаться в прозе: сервис
 * отдаёт `{"text": …}`. Поэтому здесь нет ни [com.point.core.flow.TRANSCRIBE_PROMPT], ни разбора
 * маркеров — они принадлежат тому движку, который слушает моделью общего назначения.
 *
 * **Языка в запросе нет намеренно.** У владельца записи и на украинском, и на русском; форсированный
 * язык превратил бы чужую речь в её транслитерацию — то есть в уверенно неправильный текст.
 *
 * **Сути Whisper не даёт**, и выдумывать её здесь нечем: [Transcription.Heard.summary] остаётся
 * пустой, а суть добирает [SummarizingSpeechToText] отдельным дешёвым текстовым запросом.
 *
 * Адрес и модель приходят конструктором (из `BuildConfig`, в `DataModule`), а не читаются здесь, —
 * как у [GeminiLlmClient] и [UnstructuredAtomRecognizer]: сборка запроса и разбор ответа обязаны
 * проверяться подделкой независимо от того, что лежит в `local.properties`.
 *
 * **Ключ — функция, а не строка, и это не мелочь (#467).** Раньше он захватывался при сборке графа
 * из `BuildConfig.GROQ_API_KEY`, то есть из ключа СБОРКИ. В раздаваемой сборке такого ключа нет
 * вовсе, поэтому Whisper там не включался никогда; а человек, прочитавший «нет ключа Groq», шёл на
 * экран ключей, вводил ключ Groq — и не менялось ничего, потому что тот ключ уезжал только в
 * цепочку моделей. Теперь ключ спрашивается на каждый вызов, и введённый минуту назад работает
 * сразу — без пересборки графа и без перезапуска приложения.
 */
class GroqWhisperSpeechToText(
    private val http: HttpFiles,
    private val apiKey: () -> String,
    private val baseUrl: String,
    private val model: String,
) : SpeechToText {

    /** Есть ли ключ прямо сейчас. Без него движок выпадает из очереди — см. [FirstHeardSpeechToText]. */
    val configured: Boolean get() = apiKey().isNotBlank()

    /** Ключ Groq — единственный, который включает Whisper; так это человеку и говорится. */
    override fun missingKey(): SpeechKeyNeed? =
        if (configured) null else SpeechKeyNeed("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID)

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) error("Whisper не настроен — нужен ключ Groq. $KEY_SETTINGS_CALL")

        // Отказ до сети: формат, которого движок не читает, честнее назвать словами здесь, чем
        // получить HTTP 400 и показать человеку кусок чужого JSON. Список общий с моделью общего
        // назначения — одно место на вопрос «что мы вообще принимаем» (#223).
        val mime = modelReadableAudio(obj.mime, obj.metadata["name"])
            ?: error("Этот формат записи модель не читает — подойдут ogg, mp3, m4a, wav, flac")

        val file = File(obj.uri.value)
        val bytes = file.length()
        if (bytes > MAX_WHISPER_BYTES) {
            error("Запись слишком большая (${bytes / (1024 * 1024)} МБ) — Whisper принимает до 25 МБ")
        }

        val res = http.postMultipart(
            url = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/') + PATH,
            // `User-Agent` здесь не называется: его ставит транспорт всем исходящим запросам
            // ([pointHeaders]). Именно этот сервис однажды числился мёртвым из-за безымянных
            // запросов, и второй правды об одном заголовке заводить нельзя — она разъедется.
            headers = mapOf("Authorization" to "Bearer $key"),
            parts = listOf(
                // Имя файла синтетическое: сервис смотрит на расширение, а настоящее имя записи —
                // это имя из чужого мессенджера, и чужому сервису оно не нужно.
                FormPart.Binary("file", "voice.${extensionOf(mime)}", mime, file.readBytes()),
                FormPart.Field("model", model),
                FormPart.Field("response_format", "json"),
                // language НЕ передаётся — см. договор класса.
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))
        heardOf(res.body)
    }

    /**
     * Ответ сервиса → расшифровка.
     *
     * Пустой текст — это [Transcription.Silence], а не сбой: движок дошёл, послушал и речи не нашёл.
     * Очередь движков на тишине останавливается, поэтому запись не уедет во второй сервис за тем же
     * самым ответом.
     *
     * **Цена, названная вслух:** тишину Whisper чаще отдаёт выдумкой, а не пустотой — замер
     * 04.08.2026 на секунде цифровой тишины вернул `" Thank you."`. Сигнала, по которому выдумку
     * можно отличить от речи, у сервиса нет (`verbose_json` на том же файле даёт `no_speech_prob: 0`),
     * поэтому порога здесь нет: сторож, который на замере спит, хуже отсутствующего. Разбор — в
     * `docs/DECISIONS.md` (#223).
     */
    private fun heardOf(body: String): Transcription {
        // #541: кусок сырого ответа из отказа убран — он доезжал до экрана человека под объектом.
        val text = runCatching { JSONObject(body).optString("text") }
            .getOrElse { error("Whisper - ответ не разобран, пробуем следующий движок") }
            .trim()
        return if (text.isEmpty()) Transcription.Silence else Transcription.Heard(text)
    }

    /** Отказ человеческими словами: 429 — это «сейчас часто», а не «сломалось». */
    private fun refusal(code: Int): String = when (code) {
        401 -> "Whisper - ключ Groq не принят (401)"
        // 403 у Groq — не обязательно «ключ не тот»: так же выглядит отказ самого сервиса пустить
        // запрос. Назвать одну причину значило бы отправить человека чинить исправный ключ.
        403 -> "Whisper - Groq не пустил запрос (403): ключ не принят или отказал сам сервис"
        413 -> "Whisper - запись слишком большая для бесплатного лимита"
        429 -> "Whisper - слишком часто (429), пробуем следующий движок"
        else -> "Whisper - сервис отказал (код $code), пробуем следующий движок"
    }

    /** Канонический тип → расширение, которое сервис ждёт в имени файла. */
    private fun extensionOf(mime: String): String = EXTENSIONS[mime] ?: "ogg"

    private companion object {
        const val PATH = "/audio/transcriptions"

        /** Запасной адрес: пустая строка в `local.properties` не должна превращать ключ в мусор. */
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        /** Предел бесплатного плана Groq на файл. Свой, а не общий с вложением модели (15 МБ). */
        const val MAX_WHISPER_BYTES = 25L * 1024 * 1024

        val EXTENSIONS: Map<String, String> = mapOf(
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3",
            "audio/wav" to "wav",
            "audio/flac" to "flac",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            // aiff Groq не обещает вовсе. Отказывать за него здесь нечестно — пусть скажет сам, а
            // очередь движков передаст запись тому, кто aiff читает.
            "audio/aiff" to "aiff",
        )
    }
}
