package com.point.core.flow

import com.point.core.model.PointObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Очередь движков расшифровки: **первый, кто услышал, выигрывает** (#223).
 *
 * Появилась не «на будущее», а по замеру 04.08.2026: бесплатная квота модели общего назначения —
 * 20 запросов в СУТКИ (`HTTP 429, generate_content_free_tier_requests, limit: 20`), то есть
 * расшифровка на ней кончается за один вечер. Whisper на Groq на тех же трёх записях владельца
 * прочитал украинскую речь дословно и даром. Значит, движков должно быть больше одного, и очередь
 * — это не запасной путь, а основной способ дожить до ответа.
 *
 * Похоже на `FallbackLlmClient`, но **не он**: у этого контракта другой исход.
 *
 * - **Тишина — ответ, а не отказ.** [Transcription.Silence] возвращается сразу и второму движку
 *   запись не уходит: «речи не слышно» — это то, что движок услышал, и переспрашивать чужие уши
 *   значило бы отправить личную запись в ещё один сервис ради того же самого ответа.
 * - **Отказ — это исключение.** Нет ключа, нет сети, провайдер сказал 429 — идём к следующему.
 * - **Все отказали — говорим, что именно не вышло.** Один движок отвечает своими словами (они уже
 *   называют причину), несколько — короткой сводкой; пустой расшифровки не бывает никогда.
 *
 * Отмена человеком (#288) — не отказ движка: [CancellationException] уходит наверх, а не роняет
 * запись в следующий сервис. Иначе тап по «Отмена» посреди долгой записи запускал бы ещё одну
 * отправку — ровно то, чего человек только что попросил не делать.
 *
 * **Ненастроенный движок — не отказ, а отсутствие.** У кого нет ключа, того в очереди сегодня
 * просто нет: спрашивать его значило бы положить «нет ключа» в сводку рядом с настоящими причинами
 * и утопить их в шуме. Зато когда ключа нет НИ У КОГО, отказ приходит один — и называет, чей ключ
 * какой движок включает, вместо прежнего «задайте свой ключ» (#467).
 *
 * Чистый Kotlin и никаких синглтонов: список движков приходит снаружи (в приложении — из DI),
 * поэтому порядок очереди виден в одном месте и судится юнит-тестом.
 */
class FirstHeardSpeechToText(
    private val engines: List<SpeechToText>,
) : SpeechToText, SpeechReadiness {

    override fun missingKeys(): List<SpeechKeyNeed> = speechKeyNeeds(engines)

    /** Очередь не готова, только когда не готов ни один: пока слышит хоть кто-то, ключ не нужен. */
    override fun missingKey(): SpeechKeyNeed? = missingKeys().firstOrNull()

    override suspend fun transcribe(obj: PointObject): Transcription {
        if (engines.isEmpty()) error(NO_SPEECH_ENGINES)
        // Ключа нет ни у кого — сказать это надо ОДИН раз и по делу, до сети и до ожидания.
        val needs = missingKeys()
        if (needs.isNotEmpty()) error(speechKeyRefusal(needs))

        val refusals = mutableListOf<String>()
        for (engine in engines) {
            if (engine.missingKey() != null) continue // без ключа движка сегодня нет — это не сбой
            try {
                // И Heard, и Silence — ответ движка. Дальше по очереди идём только от исключения.
                return engine.transcribe(obj)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refusals += (e.message ?: e.javaClass.simpleName).substringBefore('\n').take(120)
            }
        }
        error(refusalOf(refusals))
    }

    /**
     * Что сказать человеку, когда не дошёл никто.
     *
     * Единственный отказ передаётся дословно: движок уже назвал причину («нет ключа», «нет
     * подключения к интернету»), и обёртка над ней добавила бы только шум. Несколько — сводкой из
     * непохожих причин, а не стеной повторов от каждого провайдера.
     */
    private fun refusalOf(refusals: List<String>): String {
        val distinct = refusals.distinct()
        return when {
            distinct.isEmpty() -> NO_SPEECH_ENGINES
            distinct.size == 1 -> distinct.first()
            else -> "Расшифровать не удалось — " + distinct.take(2).joinToString("; ")
        }
    }
}
