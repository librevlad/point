package com.point.core.flow

import com.point.core.model.PointObject
import kotlin.coroutines.cancellation.CancellationException

class FirstHeardSpeechToText(
    private val engines: List<SpeechToText>,
) : SpeechToText, SpeechReadiness {

    override fun missingKeys(): List<SpeechKeyNeed> = speechKeyNeeds(engines)

    override fun missingKey(): SpeechKeyNeed? = missingKeys().firstOrNull()

    override suspend fun transcribe(obj: PointObject): Transcription {
        if (engines.isEmpty()) error(NO_SPEECH_ENGINES)

        val needs = missingKeys()
        if (needs.isNotEmpty()) error(speechKeyRefusal(needs))

        val refusals = mutableListOf<String>()
        for (engine in engines) {
            if (engine.missingKey() != null) continue
            try {

                return engine.transcribe(obj)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refusals += (e.message ?: e.javaClass.simpleName).substringBefore('\n').take(120)
            }
        }
        error(refusalOf(refusals))
    }

    private fun refusalOf(refusals: List<String>): String {
        val distinct = refusals.distinct()
        return when {
            distinct.isEmpty() -> NO_SPEECH_ENGINES

            // Одиночный отказ доходит своими словами: движок уже сказал человеку, что
            // случилось, и пересказывать его сводкой нечего.
            distinct.size == 1 -> distinct.first()

            // Сводка — общая (#1237). Своей ветки про сеть у речи не было вовсе, и офлайн
            // человек читал склейку транспортных отказов вместо причины, которую он может
            // устранить.
            else -> summariseCloudErrors(distinct, WHAT_FAILED)
        }
    }

    private companion object {

        /** Глагол этой цепочки для общей сводки отказов (#1237). */
        const val WHAT_FAILED = "расшифровать"
    }
}
