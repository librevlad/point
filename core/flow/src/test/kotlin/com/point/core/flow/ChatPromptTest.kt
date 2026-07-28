package com.point.core.flow

import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI chat prompt is built with the object's content and the conversation history — pure,
 *  JVM-tested — so replies see the whole thread, not just the latest question. */
class ChatPromptTest {

    @Test
    fun `carries the object content, the history in order, and the new message, then prompts the assistant`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "что это?"),
            ChatMessage(ChatRole.ASSISTANT, "чек магазина"),
        )
        val p = buildChatPrompt(ObjectKind.TEXT, "ИТОГО 693,40 грн", history, "сколько всего?")

        assertTrue("content is included", "693,40" in p)
        assertTrue("history before new message", p.indexOf("чек магазина") < p.indexOf("сколько всего?"))
        assertTrue("user turn before assistant turn", p.indexOf("что это?") < p.indexOf("чек магазина"))
        assertTrue("ends inviting the assistant to answer", p.trimEnd().endsWith("Ассистент:"))
    }

    @Test
    fun `omits the content section when the object has none`() {
        val p = buildChatPrompt(ObjectKind.IMAGE, null, emptyList(), "что на фото?")
        assertTrue("что на фото?" in p)
        assertFalse("Содержимое" in p)
    }
}
