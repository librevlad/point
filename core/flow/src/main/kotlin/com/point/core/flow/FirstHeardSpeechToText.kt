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
            distinct.size == 1 -> distinct.first()
            else -> "Расшифровать не удалось — " + distinct.take(2).joinToString("; ")
        }
    }
}
