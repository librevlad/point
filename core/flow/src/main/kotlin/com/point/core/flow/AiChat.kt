package com.point.core.flow

import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

fun interface AiChatResponder {

    /**
     * [content] — содержимое объекта, каким Point знает его сейчас; `null` — знания нет.
     *
     * Добывает его разговор, и один раз на разговор (#1241): прежде каждая реплика заново
     * поднимала весь документ или весь текстовый файл ради одних и тех же первых
     * [MAX_CHAT_CONTENT] символов.
     */
    suspend fun reply(
        obj: PointObject,
        content: String?,
        history: List<ChatMessage>,
        message: String,
    ): String
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

/** Сколько содержимого объекта уходит модели вместе с вопросом — столько и добывается. */
const val MAX_CHAT_CONTENT: Int = 16_000
