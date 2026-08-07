package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.SUMMARIZE_PROMPT
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.parseSummary
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SummarizingSpeechToText(
    private val engine: SpeechToText,
    private val llm: LlmClient,
) : SpeechToText {

    override fun missingKey(): SpeechKeyNeed? = engine.missingKey()

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {
        val heard = engine.transcribe(obj)
        if (heard !is Transcription.Heard || heard.summary.isNotBlank()) return@withContext heard

        val summary = try {
            val answer = llm.run(textStandIn(obj), SUMMARIZE_PROMPT + heard.text)
            parseSummary(File(answer.uri.value).readText(), heard.text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (summary.isBlank()) heard else heard.copy(summary = summary)
    }

    private fun textStandIn(obj: PointObject): PointObject =
        obj.copy(mime = "text/plain", metadata = obj.metadata - "name")
}
