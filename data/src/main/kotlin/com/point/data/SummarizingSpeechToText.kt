package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.SUMMARIZE_PROMPT
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.parseSummary
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Суть для движка, который умеет только слушать (#223).
 *
 * [GroqWhisperSpeechToText] отдаёт дословный текст и ничего больше — сути у Whisper нет вовсе.
 * Договор контракта пустую суть позволяет и догадкой её заполнять запрещает, но человеку обещана
 * именно суть: ради неё он и не слушает три минуты. Поэтому после успешной расшифровки — ОДИН
 * дешёвый **текстовый** запрос к общей цепочке [LlmClient], и не более того.
 *
 * Что здесь важно и легко сломать:
 *
 * - **Это по-прежнему одно действие.** Цепочка — это когда Point сам запускает следующее действие,
 *   которого человек не выбирал. Сколько работы делает одно действие внутри себя — его собственный
 *   договор, и он записан в `docs/DECISIONS.md` (#223) ещё до Whisper.
 * - **Не вышло — не беда.** Нет ключа, нет сети, модель ответила `NO_SUMMARY` — суть остаётся
 *   пустой, расшифровка доезжает целиком. Уронить из-за сводки уже добытые слова человека было бы
 *   обменом ценного на приятное.
 * - **Уже есть суть — второго запроса нет.** Движок, который отвечает и текстом, и сутью
 *   ([LlmSpeechToText]), проходит насквозь бесплатно.
 * - **Тишина не ходит за сутью.** Пересказывать нечего, и тратить на это чужую квоту незачем.
 * - **Наружу уезжает текст, а не запись.** Объект для запроса объявляется текстовым, и у него
 *   снимается имя файла: по имени `PTT-2026.ogg` клиент модели общего назначения узнаёт звук и
 *   приложил бы к запросу саму запись — второй отправкой голоса человека и второй тратой той самой
 *   суточной квоты, из-за которой Whisper и стал первым.
 */
class SummarizingSpeechToText(
    private val engine: SpeechToText,
    private val llm: LlmClient,
) : SpeechToText {

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {
        val heard = engine.transcribe(obj)
        if (heard !is Transcription.Heard || heard.summary.isNotBlank()) return@withContext heard

        val summary = try {
            val answer = llm.run(textStandIn(obj), SUMMARIZE_PROMPT + heard.text)
            parseSummary(File(answer.uri.value).readText(), heard.text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "" // Суть не обязательство: не дали — значит её нет, и расшифровка от этого не пропадает.
        }
        if (summary.isBlank()) heard else heard.copy(summary = summary)
    }

    /** Модель спрашивают ПРО ТЕКСТ: расшифровка уже у нас, и запись второй раз никуда не уезжает. */
    private fun textStandIn(obj: PointObject): PointObject =
        obj.copy(mime = "text/plain", metadata = obj.metadata - "name")
}
