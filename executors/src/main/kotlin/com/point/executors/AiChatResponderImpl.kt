package com.point.executors

import com.point.core.flow.AiChatResponder
import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.buildChatPrompt
import com.point.core.model.ChatMessage
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiChatResponderImpl(
    private val llm: LlmClient,
    private val pdfText: PdfTextExtractor,
) : AiChatResponder {

    override suspend fun reply(obj: PointObject, history: List<ChatMessage>, message: String): String =
        withContext(Dispatchers.IO) {
            val prompt = buildChatPrompt(obj.state.kind, contentOf(obj), history, message)
            val answer = llm.run(obj, prompt)
            File(answer.uri.value).readText()
        }

    /**
     * Содержимое объекта уходит модели вместе с вопросом (#780).
     *
     * Раньше читался только текстовый файл, и разговор о PDF шёл вслепую: экран открыт над
     * «3. План проведення перевірки.pdf», а модель отвечала «мне нужно знать, о каком
     * документе идёт речь». Документ лежал в руках у Point и в запрос не попадал.
     *
     * Прочитанное раньше берётся первым: сидекар OCR и знание из QR — тот же источник, что
     * у «Понять» и «Перевести», и второй раз читать файл незачем.
     */
    private suspend fun contentOf(obj: PointObject): String? {
        entitySourceText(obj).takeIf { it.isNotBlank() && obj.state.kind != ObjectKind.URL }?.let { return it }

        return when (obj.state.kind) {
            ObjectKind.PDF -> runCatching { pdfText.extractText(obj) }.getOrNull()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
