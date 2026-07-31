package com.point.executors

import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.alternativesOf
import com.point.core.flow.layoutOf
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Понять» — три AI-кнопки одним действием (#260). Пины мигрировали из тестов
 * DeepUnderstand/Classify/CollectPlus: строгие построчные контракты, роль пишется текстом
 * элемента страницы, выдуманное отбрасывается, находки сливаются голосованием.
 */
class UnderstandRealizerTest {

    private var lastPrompt: String? = null
    private var lastLlmObject: PointObject? = null

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            lastLlmObject = obj
            val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private val document = """
        Нова Пошта
        ТОВ «Агротрейд»
        Відділення №9, вул. Хрещатик, 1
        20 4514 9154 9395
    """.trimIndent()

    private fun textObject(content: String = document, metadata: Map<String, String> = emptyMap()): PointObject {
        val f = File.createTempFile("point-doc", ".txt").apply { deleteOnExit(); writeText(content) }
        return PointObject("doc", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT), metadata)
    }

    private fun realizer(answer: String) = UnderstandRealizer(llm(answer))

    // --- Одно действие, обе стадии: значения и роли из одного ответа ---

    @Test
    fun `факты и роли выходят из одного вызова — стадии пайплайна больше не кнопки`() = runTest {
        val result = realizer("PHONE=+380671234567\nsender=P2\nSUMMARY=накладная на посылку")
            .perform(textObject())

        assertTrue(result is ActionResult.Success)
        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("+380671234567", meta["entity.phone"])
        // Роль — собственным текстом элемента страницы, не формулировкой модели (#222, шаг 6).
        assertEquals("ТОВ «Агротрейд»", meta["graph.role.sender"])
        assertEquals("накладная на посылку", meta["semantic.summary"])
        assertEquals("understand", meta["op"])
    }

    @Test
    fun `тот же объект, те же байты — понимание богаче, uri не меняется`() = runTest {
        val obj = textObject()
        val result = realizer("PHONE=+380671234567").perform(obj) as ActionResult.Success

        assertEquals(obj.uri, result.result.uri)
        assertEquals(obj.state.kind, result.result.type)
    }

    @Test
    fun `TRACK — законный ключ контракта, у идентификатора нет универсальной формы`() = runTest {
        val result = realizer("TRACK=RA123456789UA").perform(textObject()) as ActionResult.Success

        assertEquals("RA123456789UA", result.result.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `выдуманный идентификатор роли отброшен, факты того же ответа живут`() = runTest {
        val result = realizer("PHONE=+380671234567\nsender=P99").perform(textObject())

        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("+380671234567", meta["entity.phone"])
        assertNull(meta["graph.role.sender"])
    }

    @Test
    fun `NONE — честное «ничего», recoverable отказ`() = runTest {
        val result = realizer("NONE").perform(textObject())

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `проза вместо контракта не рождает ни фактов, ни ролей`() = runTest {
        val result = realizer("Конечно! Отправителем является ТОВ «Агротрейд».").perform(textObject())

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `пустой документ не доходит до модели`() = runTest {
        val result = realizer("PHONE=+380671234567").perform(textObject(content = "   "))

        assertTrue(result is ActionResult.Failure)
        assertNull("LLM не должен вызываться на пустом тексте", lastPrompt)
    }

    // --- Контракт значений (пины DeepUnderstandTest) ---

    @Test
    fun `выдуманный моделью TYPE не сохраняется`() = runTest {
        val result = realizer("TYPE=POEM\nPHONE=+380671234567").perform(textObject()) as ActionResult.Success

        assertNull(result.result.metadata["semantic.type"])
    }

    @Test
    fun `TYPE из закрытого списка становится semantic type`() = runTest {
        val result = realizer("TYPE=PURCHASE\nSUMMARY=чек із супермаркету").perform(textObject()) as ActionResult.Success

        assertEquals("purchase", result.result.metadata["semantic.type"])
    }

    @Test
    fun `первый ответ на ключ побеждает, пустые значения отброшены`() {
        val found = parseUnderstanding("PHONE=+380671234567\nPHONE=+380000000000\nEMAIL=")

        assertEquals(mapOf("entity.phone" to "+380671234567"), found)
    }

    // --- Слияние голосованием (пин DeepUnderstand: платная догадка не выигрывает) ---

    @Test
    fun `известный факт не затирается — расхождение видно в alt`() = runTest {
        val known = textObject(metadata = mapOf("entity.phone" to "+380671234567"))

        val result = realizer("PHONE=+380679999999").perform(known) as ActionResult.Success

        assertEquals("+380671234567", result.result.metadata["entity.phone"])
        assertEquals(
            listOf("+380671234567", "+380679999999"),
            alternativesOf(result.result.metadata, "entity.phone"),
        )
    }

    /** Ревью #260: подтверждение моделью первого номера не имеет права стирать запись о втором
     *  настоящем номере страницы — второй живёт в .more, который mergeFacts не трогает. */
    @Test
    fun `подтверждение первого трека не стирает второй номер страницы`() = runTest {
        val two = com.point.core.flow.altValue(listOf("20 4514 9154 9395", "20451491549396"))
        val known = textObject(
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + com.point.core.flow.META_MORE_SUFFIX to two,
            ),
        )

        val result = realizer("TRACK=20 4514 9154 9395").perform(known) as ActionResult.Success

        assertEquals("20 4514 9154 9395", result.result.metadata[META_ENTITY_TRACK])
        assertEquals(
            listOf("20 4514 9154 9395", "20451491549396"),
            com.point.core.flow.moreOf(result.result.metadata, META_ENTITY_TRACK),
        )
    }

    // --- Только текст уходит с устройства (пины Classify/DeepUnderstand) ---

    @Test
    fun `картинка с распознанным текстом уходит в облако текстом, не пикселями`() = runTest {
        val sidecar = File.createTempFile("point-ocr", ".txt").apply { deleteOnExit(); writeText(document) }
        val image = PointObject(
            "img", "image/png", ScratchRef("/tmp/x.png"),
            ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT)),
            metadata = mapOf(META_OCR_TEXT_REF to sidecar.absolutePath),
        )

        realizer("PHONE=+380671234567").perform(image)

        assertEquals("text/plain", lastLlmObject!!.mime)
        assertNull(lastLlmObject!!.metadata[META_OCR_TEXT_REF])
        assertTrue(lastPrompt!!.contains("Агротрейд"))
    }

    // --- Промпт: граф не имеет пути внутрь (пины ClassifierTest) ---

    @Test
    fun `промпт несёт элементы с идентификаторами и роли`() {
        val prompt = understandPrompt(layoutOf(document))

        assertTrue(prompt.contains("P2: ТОВ «Агротрейд»"))
        assertTrue(prompt.contains("sender"))
        assertTrue(prompt.contains("carrier"))
        assertTrue(prompt.contains("TRACK"))
    }

    @Test
    fun `промпт не называет ни вид, ни отношение — графу нет пути в строку`() {
        val prompt = understandPrompt(layoutOf(document))

        assertFalse(prompt.contains("Organization"))
        assertFalse(prompt.contains(KIND_ORGANIZATION.name))
        assertFalse(prompt.contains("PointObject"))
        assertFalse(prompt.contains("issued_by"))
    }

    // --- Capability: одна кнопка вместо трёх ---

    @Test
    fun `принимает текст и распознанную картинку, не сырое фото`() {
        val cap = UnderstandCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `платное, медленное, сетевое — никогда не на первом экране`() {
        val meta = UnderstandCapability().meta

        assertTrue(meta.network)
        assertTrue(meta.auth)
        assertEquals(com.point.core.flow.Cost.PAID, meta.cost)
        assertEquals(com.point.core.flow.Latency.SLOW, meta.latency)
    }

    @Test
    fun `label — «Понять», produces — тот же state`() {
        val cap = UnderstandCapability()
        val state = ObjectState(ObjectKind.TEXT)

        assertEquals("Понять", cap.label(state))
        assertEquals(state, cap.produces(state))
    }
}
