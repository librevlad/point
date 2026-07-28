package com.point.executors

import com.point.core.flow.AiChatResponder
import com.point.core.flow.LlmClient
import com.point.core.flow.buildChatPrompt
import com.point.core.model.ChatMessage
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The AI chat behind the shared [LlmClient] (#4). Each turn builds a history-aware prompt and reads
 * the model's answer back as text. Text objects put their content in the prompt; images/PDFs are
 * seen by the model through [obj] itself (vision), same as the one-shot AiRealizer.
 */
class AiChatResponderImpl(
    private val llm: LlmClient,
) : AiChatResponder {

    override suspend fun reply(obj: PointObject, history: List<ChatMessage>, message: String): String =
        withContext(Dispatchers.IO) {
            val content = if (obj.state.kind == ObjectKind.TEXT) {
                runCatching { File(obj.uri.value).readText() }.getOrNull()
            } else {
                null
            }
            val prompt = buildChatPrompt(obj.state.kind, content, history, message)
            val answer = llm.run(obj, prompt)
            File(answer.uri.value).readText()
        }
}
