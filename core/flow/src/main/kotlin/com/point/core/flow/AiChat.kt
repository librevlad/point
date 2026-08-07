package com.point.core.flow

import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

fun interface AiChatResponder {

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

        else -> "объектом"
    }
    return "Ты — помощник Point. Помогаешь пользователю разобраться с этим $subject. " +
        "Отвечай кратко, по делу и по-русски; не выдумывай фактов."
}

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
