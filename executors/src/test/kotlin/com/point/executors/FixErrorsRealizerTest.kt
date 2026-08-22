package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * «Исправить ошибки» и «Исправить сильнее» (#666). Обе двери — знание того же объекта;
 * разница ровно одна: во вторую уходит и сам снимок.
 *
 * У текстового объекта первая ступень проверяет сам текст (#1023): он и есть знание.
 */
class FixErrorsRealizerTest {

    @get:Rule val temp = TemporaryFolder()

    private var lastObject: PointObject? = null
    private var lastPrompt: String? = null

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastObject = obj
            lastPrompt = prompt
            val f = File.createTempFile("fix-", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun fixer(answer: String) = FixErrorsRealizer(llm(answer), testKnowledge(), FileBackedStore)

    private val sender = META_GRAPH_ROLE_PREFIX + "sender"

    private fun photo(metadata: Map<String, String> = mapOf(sender to "Паринкн")) =
        PointObject("img", "image/jpeg", ScratchRef("/tmp/скан.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    /** Текст из живого прогона владельца (17.08.2026): пять опечаток и одна дата. */
    private val typed = "Превет, Иван! Эт тестовый тектс с пятью ашибками и опичатками, проверка 17.08.2026."

    private fun text(body: String = typed, metadata: Map<String, String> = mapOf(META_ENTITY_PREFIX + "date" to "17.08.2026")) =
        PointObject(
            "t", "text/plain",
            ScratchRef(temp.newFile().apply { writeText(body) }.absolutePath),
            ObjectState(ObjectKind.TEXT), metadata,
        )

    private val ready = AiReadiness { true }

    @Test
    fun `опечатка исправлена, прежнее значение осталось следом`() = runTest {
        val result = fixer("1 = Паринкін").perform(photo(), null)

        assertTrue("ожидалось знание, вышло: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        assertEquals("Исправлено: 1", done.message)
        assertEquals("Паринкін", done.findings!!.metadata[sender])
        assertTrue("след прежнего значения потерян", done.findings!!.metadata.containsKey(sender + META_ALT_SUFFIX))
    }

    @Test
    fun `исправление — знание того же объекта, нового объекта не рождается`() = runTest {
        val done = fixer("1 = Паринкін").perform(photo(), null) as ActionResult.Done

        assertTrue("понимание не смеет плодить объекты", done.findings!!.objects.isEmpty())
    }

    @Test
    fun `нечего править — это исход, а не отказ и не пустая правка`() = runTest {
        val result = fixer("NONE").perform(photo(), null)

        val done = result as ActionResult.Done
        assertEquals("Ошибок не нашлось — знание оставлено как было", done.message)
        assertNull("пустая правка не должна ехать знанием", done.findings)
    }

    @Test
    fun `первая ступень снимок наружу не отправляет`() = runTest {
        fixer("NONE").perform(photo(), null)

        assertFalse(
            "наружу ушёл снимок, хотя просили проверить только знание: ${lastObject!!.mime}",
            lastObject!!.mime.startsWith("image/"),
        )
    }

    @Test
    fun `«сильнее» отправляет сам снимок и говорит об этом модели`() = runTest {
        FixErrorsStrongerRealizer(llm("NONE")).perform(photo(), null)

        assertTrue("снимок обязан уйти на сверку", lastObject!!.mime.startsWith("image/"))
        assertTrue("модель не предупреждена, что источник приложен", "снимком" in lastPrompt!!)
    }

    @Test
    fun `подтверждённое человеком в модель не уходит`() = runTest {
        val confirmed = photo(
            mapOf(sender to "Паринкн", sender + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire),
        )

        val result = fixer("1 = Паринкін").perform(confirmed, null)

        assertTrue("исправлять было нечего — слово человека трогать нельзя", result is ActionResult.Failure)
        assertNull("до модели дело дойти не должно", lastPrompt)
    }

    @Test
    fun `дверь появляется только там, где есть что исправлять`() {
        val cap = FixErrorsCapability(ready)
        val withKnowledge = GraphState(photo())
        val bare = GraphState(photo(metadata = emptyMap()))

        assertTrue(cap.accepts(withKnowledge))
        assertFalse("на объекте без знания двери быть не должно", cap.accepts(bare))
    }

    @Test
    fun `«сильнее» предлагается только там, где есть источник для сверки`() {
        val cap = FixErrorsStrongerCapability(ready)

        assertTrue(cap.accepts(GraphState(photo())))
        assertFalse("у текста сверять знание не с чем", cap.accepts(GraphState(text())))
    }

    @Test
    fun `платное, сетевое, требующее ключа — обе двери`() {
        listOf(FixErrorsCapability(ready).meta, FixErrorsStrongerCapability(ready).meta).forEach { meta ->
            assertTrue(meta.network)
            assertTrue(meta.auth)
            assertEquals(com.point.core.flow.Cost.PAID, meta.cost)
        }
    }

    // ---- Текстовый объект (#1023): проверяется сам текст, он и есть знание ----

    @Test
    fun `у текста в модель уходит сам текст, а не список значений`() = runTest {
        fixer("NONE").perform(text(), null)

        assertTrue("текст не дошёл до модели: $lastPrompt", typed in lastPrompt!!)
        assertFalse("модели снова выдали сводку значений вместо текста", "1 = 17.08.2026" in lastPrompt!!)
    }

    @Test
    fun `исправленный текст ложится знанием того же объекта, дельта видна в итоге`() = runTest {
        val result = fixer("Превет = Привет\nтектс = текст").perform(text(), null)

        assertTrue("ожидалось знание, вышло: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        val landed = done.findings!!.metadata[META_OCR_TEXT_REF]
        assertTrue("исправленный текст не стал прочтением объекта", landed != null)
        val read = File(landed!!).readText()
        assertTrue("правка не легла в текст: $read", "Привет, Иван! Эт тестовый текст с пятью ашибками" in read)
        assertTrue("нового объекта быть не должно", done.findings!!.objects.isEmpty())
        listOf("Превет", "Привет", "тектс", "текст").forEach { word ->
            assertTrue("в итоге не видно дельты «$word»: ${done.message}", word in done.message)
        }
    }

    @Test
    fun `текст без правок — «ошибок не нашлось» сказано про проверенный текст`() = runTest {
        val done = fixer("NONE").perform(text(), null) as ActionResult.Done

        assertTrue("модель текста не видела, а итог — приговор тексту", typed in lastPrompt!!)
        assertNull("пустая правка не должна ехать знанием", done.findings)
        assertTrue("без правок итог не может звучать как «исправлено»: ${done.message}", "Исправлено" !in done.message)
    }

    @Test
    fun `повторная правка идёт поверх уже исправленного текста, а не исходника`() = runTest {
        val earlier = temp.newFile().apply { writeText("Привет, Иван! Эт тестовый текст.") }
        val once = text(metadata = mapOf(META_OCR_TEXT_REF to earlier.absolutePath))

        fixer("NONE").perform(once, null)

        assertTrue("в модель ушёл исходник, а не текущее прочтение: $lastPrompt", "Привет, Иван! Эт тестовый текст." in lastPrompt!!)
        assertFalse("исходник с уже исправленными опечатками не должен уходить снова", typed in lastPrompt!!)
    }

    @Test
    fun `дверь на тексте открыта и без единого значения — текст и есть знание`() {
        val cap = FixErrorsCapability(ready)

        assertTrue(cap.accepts(GraphState(text(metadata = emptyMap()))))
    }
}
