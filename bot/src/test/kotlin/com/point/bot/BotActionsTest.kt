package com.point.bot

import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BotActionsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `an LLM capability accepts its kinds and its realizer sends the prompt`() = runTest {
        val cap = LlmBotCapability("understand", "Понять", setOf(ObjectKind.TEXT, ObjectKind.IMAGE), 10)
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.ZIP)))

        var seen: String? = null
        val out = File(tmp.root, "a.md").apply { writeText("суть") }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seen = prompt
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(out.absolutePath))
            }
        }
        val src = File(tmp.root, "t.txt").apply { writeText("длинный текст") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val result = LlmBotRealizer("understand", "Перескажи кратко.", llm).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertTrue(seen!!.contains("Перескажи кратко"))
    }

    @Test
    fun `qr-make turns text into a decodable QR image`() = runTest {
        val src = File(tmp.root, "u.txt").apply { writeText("https://point.example/x") }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val result = QrMakeRealizer(tmp.newFolder("s")).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        val png = File((result as ActionResult.Success).result.uri.value)
        assertEquals(ObjectKind.IMAGE, result.result.type)
        assertTrue(ImageIO.read(png).width > 0) // it is a real PNG
    }

    @Test
    fun `qr-read decodes the QR that qr-make wrote - round trip`() = runTest {
        val src = File(tmp.root, "u.txt").apply { writeText("секрет-42") }
        val made = QrMakeRealizer(tmp.newFolder("s")).perform(
            PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT)), null,
        ) as ActionResult.Success
        val img = PointObject("q", "image/png", made.result.uri, ObjectState(ObjectKind.IMAGE))

        val read = QrReadRealizer(tmp.newFolder("s2")).perform(img, null)

        assertTrue(read is ActionResult.Success)
        assertEquals("секрет-42", File((read as ActionResult.Success).result.uri.value).readText())
    }
}
