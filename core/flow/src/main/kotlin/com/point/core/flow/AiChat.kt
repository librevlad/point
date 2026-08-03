package com.point.core.flow

import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/**
 * The AI chat with an object (#4). A multi-turn conversation grounded in the object: each reply sees
 * the whole thread. Behind a contract so the LLM stays fakeable and off the first screen; the impl
 * builds the prompt with [buildChatPrompt] and calls the shared [LlmClient].
 */
fun interface AiChatResponder {
    /** Answer [message] in the context of [obj] and the prior [history]. Returns plain/markdown text. */
    suspend fun reply(obj: PointObject, history: List<ChatMessage>, message: String): String
}

private fun chatSystemPrompt(kind: ObjectKind): String {
    val subject = when (kind) {
        ObjectKind.IMAGE -> "изображением"
        ObjectKind.PDF -> "PDF-документом"
        ObjectKind.TEXT -> "текстом"
        ObjectKind.OFFICE -> "офисным документом"
        ObjectKind.URL -> "ссылкой"
        ObjectKind.AUDIO -> "аудиозаписью"
        ObjectKind.ZIP, ObjectKind.COLLECTION -> "набором файлов"
        // Extraction kinds are open (#222) — an Organization or an Identifier is still just
        // «an object» to talk about, so UNKNOWN and everything new share one wording.
        else -> "объектом"
    }
    return "Ты — помощник Point. Помогаешь пользователю разобраться с этим $subject. " +
        "Отвечай кратко, по делу и по-русски; не выдумывай фактов."
}

/** Build the chat prompt from the object's [content] (already extracted), the [history], and the new
 *  [message], ending with an open «Ассистент:» turn. Pure — JVM-tested in ChatPromptTest. */
fun buildChatPrompt(
    kind: ObjectKind,
    content: String?,
    history: List<ChatMessage>,
    message: String,
): String = buildString {
    append(chatSystemPrompt(kind))
    if (!content.isNullOrBlank()) {
        append("\n\nСодержимое объекта:\n")
        append(content.take(MAX_CHAT_CONTENT))
    }
    if (history.isNotEmpty()) {
        append("\n\nДиалог:")
        history.forEach { m ->
            append("\n")
            append(if (m.role == ChatRole.USER) "Пользователь: " else "Ассистент: ")
            append(m.text)
        }
    }
    append("\n\nПользователь: ")
    append(message)
    append("\nАссистент:")
}

private const val MAX_CHAT_CONTENT = 16_000
