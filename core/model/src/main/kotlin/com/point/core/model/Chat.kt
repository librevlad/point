package com.point.core.model

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val producedId: String? = null,

    /**
     * Исход операции, а не ответ модели (#793, решение владельца 11.08.2026: «отказ — не
     * ответ»). Неудача видна человеку в переписке, но забирать из неё нечего: объектом она не
     * становится ни при каком порядке действий.
     *
     * Признак живёт у самого сообщения, а не выводится из его текста: правило, держащееся на
     * формулировке, разойдётся с ней на первой же правке слов.
     */
    val failed: Boolean = false,
)
