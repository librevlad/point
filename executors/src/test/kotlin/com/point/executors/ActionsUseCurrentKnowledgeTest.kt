package com.point.executors

import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.DocxWriter
import com.point.core.flow.LlmClient
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Исполнитель работает с текущим знанием объекта, а не с исходным кадром (#1030, #1031, #1138).
 *
 * Кадр уже прочитан, чтение лежит в графе — и действие обязано брать его оттуда. Прежде
 * «Перевести» отвечало «текста нет» над прочитанным снимком, а «В Word» читало кадр заново и
 * собирало документ из худшего прочтения.
 */
class ActionsUseCurrentKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private val readFromFrame = "Договор №226966 от 14.08.2026"
    private val worseSecondReading = "Д0г0в0р N226Э66"

    /** Читатель, который перечитывать кадр не должен: если его спросили — правило нарушено. */
    private class Rereader(private val gives: String) : TextRecognizer {
        var asked = 0
        override suspend fun recognize(obj: PointObject): String {
            asked++
            return gives
        }
    }

    private fun readImage(): PointObject {
        val side = temp.newFile("чтение.txt").apply { writeText(readFromFrame) }
        val atoms = temp.newFile("атомы.tsv").apply {
            writeText(AtomCodec.encode(AtomLayer(listOf(Atom("w0", readFromFrame, Box(0f, 0f, 10f, 2f))))))
        }
        val frame = temp.newFile("кадр.png").apply { writeBytes(ByteArray(8)) }
        return PointObject(
            "снимок",
            "image/png",
            ScratchRef(frame.absolutePath),
            ObjectState(ObjectKind.IMAGE, setOf(com.point.core.model.Feature.HAS_TEXT)),
            metadata = mapOf(
                META_OCR_TEXT_REF to side.absolutePath,
                META_OCR_ATOMS_REF to atoms.absolutePath,
            ),
        )
    }

    private fun llmEchoing(): LlmClient = object : LlmClient {
        var seen: String? = null
        override val configured = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            seen = prompt
            val out = temp.newFile("ответ-" + System.nanoTime() + ".txt").apply { writeText(prompt) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath))
        }
    }

    @Test fun `перевод берёт прочитанное с кадра, а не отвечает «текста нет»`() = runTest {
        val llm = llmEchoing()

        val result = TranslateRealizer(llm, testKnowledge()).perform(readImage(), null)

        assertTrue("перевод отказал над прочитанным кадром- $result", result is ActionResult.Success)
        val sent = File((result as ActionResult.Success).result.uri.value).readText()
        assertTrue("в перевод уехало не то, что Point прочитал", sent.contains(readFromFrame))
    }

    @Test fun `документ собирается из знания графа, а кадр не перечитывается`() = runTest {
        val rereader = Rereader(worseSecondReading)
        var written: List<String> = emptyList()
        val docx = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef {
                written = paragraphs
                return ScratchRef(temp.newFile("док-" + System.nanoTime() + ".docx").absolutePath)
            }
        }

        val result = WordRealizer(testKnowledge(), docx, rereader).perform(readImage(), null)

        assertTrue("документ не собрался- $result", result is ActionResult.Success)
        assertEquals("кадр перечитан, хотя чтение уже было в графе", 0, rereader.asked)
        assertTrue("в документ уехало не прочитанное Point", written.any { it.contains(readFromFrame) })
    }

    @Test fun `нечитанный снимок знания не выдумывает`() = runTest {
        val frame = temp.newFile("нечитанный.png").apply { writeBytes(ByteArray(8)) }
        val fresh = PointObject("новый", "image/png", ScratchRef(frame.absolutePath), ObjectState(ObjectKind.IMAGE))

        assertEquals(null, testKnowledge().textOf(fresh))
        assertEquals(null, testKnowledge().layerOf(fresh))
    }
}
