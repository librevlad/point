package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiChatResponderImplTest {

    private val image = PointObject("i", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `reply feeds the model a history-aware prompt and returns its answer text`() = runTest {
        val answerFile = File.createTempFile("ai-reply", ".md").apply { writeText("Это чек на 693 грн.") }
        var seenPrompt = ""
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seenPrompt = prompt
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(answerFile.path))
            }
        }
        val history = listOf(
            ChatMessage(ChatRole.USER, "что это?"),
            ChatMessage(ChatRole.ASSISTANT, "чек магазина"),
        )

        val reply = AiChatResponderImpl(llm).reply(image, history, "на сколько?")

        assertEquals("Это чек на 693 грн.", reply)
        assertTrue("new message reaches the model", "на сколько?" in seenPrompt)
        assertTrue("history reaches the model", "чек магазина" in seenPrompt)
        answerFile.delete()
    }
}
