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
