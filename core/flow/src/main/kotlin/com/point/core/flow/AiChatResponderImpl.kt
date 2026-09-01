package com.point.core.flow

import com.point.core.model.ChatMessage
import com.point.core.model.PointObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiChatResponderImpl(
    private val llm: LlmClient,
) : AiChatResponder {

    /**
     * Вопрос уходит модели вместе с содержимым объекта (#780) — тем, которое подал разговор.
     *
     * Сам ответчик содержимого не добывает (#1241): он вызывается на каждую реплику, а
     * объект за разговор не меняется, и повторная добыча была бы разбором того же документа
     * заново на каждый ход.
     */
    override suspend fun reply(
        obj: PointObject,
        content: String?,
        history: List<ChatMessage>,
        message: String,
    ): String = withContext(Dispatchers.IO) {
        val prompt = buildChatPrompt(obj.state.kind, content, history, message)
        val answer = llm.run(obj, prompt)
        File(answer.uri.value).readText()
    }
}
