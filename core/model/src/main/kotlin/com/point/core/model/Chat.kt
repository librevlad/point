package com.point.core.model

/** Who spoke a turn in the AI chat (#4 — «AI-чат с объектом»). */
enum class ChatRole { USER, ASSISTANT }

/**
 * One turn of the object conversation. [text] is plain/markdown text for a spoken turn; when the
 * assistant produced a real artifact instead of talking (e.g. «сделай word»), [producedId] carries
 * the id of the new object so the UI can offer to open it — the chat that also transforms (#190).
 */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val producedId: String? = null,
)
