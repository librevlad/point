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

    private val prompts = mutableListOf<String>()
    private val lastPrompt: String? get() = prompts.lastOrNull()
    private var lastLlmObject: PointObject? = null

    /** Ответы по одному на вызов (повтор по hard-block — второй элемент); последний повторяется. */
    private fun llm(vararg answers: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            prompts += prompt
            lastLlmObject = obj
            val answer = answers[minOf(prompts.size, answers.size) - 1]
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

    private fun realizer(vararg answers: String) = UnderstandRealizer(llm(*answers))

    /** Посылочный экран со слоем слов: подпись «ТТН» рядом с треком тремя атомами; строкой
     *  ниже — «Відправник 1ваненко ван» (живой OCR-огрех владельца, #297). */
    private fun imageWithLayer(): PointObject {
        val pageText = "ТТН 20 4514 9154 9395\nВідправник 1ваненко ван"
        val layer = com.point.core.flow.AtomLayer(
            listOf(
                com.point.core.flow.Atom("m1", "ТТН", com.point.core.flow.Box(10f, 100f, 60f, 120f)),
                com.point.core.flow.Atom("w1", "20", com.point.core.flow.Box(200f, 100f, 230f, 120f)),
                com.point.core.flow.Atom("w2", "4514 9154", com.point.core.flow.Box(235f, 100f, 330f, 120f)),
                com.point.core.flow.Atom("w3", "9395", com.point.core.flow.Box(335f, 100f, 380f, 120f)),
                com.point.core.flow.Atom("m2", "Відправник", com.point.core.flow.Box(10f, 200f, 150f, 220f)),
                com.point.core.flow.Atom("w6", "1ваненко", com.point.core.flow.Box(200f, 200f, 300f, 220f)),
                com.point.core.flow.Atom("w7", "ван", com.point.core.flow.Box(305f, 200f, 350f, 220f)),
            ),
        )
        val dump = File.createTempFile("point-atoms", ".tsv").apply {
            deleteOnExit(); writeText(com.point.core.flow.AtomCodec.encode(layer))
        }
        val sidecar = File.createTempFile("point-ocr", ".txt").apply { deleteOnExit(); writeText(pageText) }
        return PointObject(
            "img", "image/png", ScratchRef("/tmp/x.png"),
            ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT)),
            metadata = mapOf(
                META_OCR_TEXT_REF to sidecar.absolutePath,
                com.point.core.flow.META_OCR_ATOMS_REF to dump.absolutePath,
            ),
        )
    }

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
        // Номер Укрпошты формата S10 с верной контрольной цифрой — правило 14 цифр его слепо.
        val result = realizer("TRACK=RA123456785UA").perform(textObject()) as ActionResult.Success

        assertEquals("RA123456785UA", result.result.metadata[META_ENTITY_TRACK])
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
    fun `повторные строки ключа — кандидаты по порядку, пустые отброшены, потолок три`() {
        val parsed = parseFieldCandidates(
            "PHONE=+380671234567\nPHONE=+380000000000\nPHONE=+380111111111\nPHONE=+380222222222\nEMAIL=",
        )

        assertEquals(
            listOf("+380671234567", "+380000000000", "+380111111111"),
            parsed.fields["entity.phone"]!!.map { it.text },
        )
        assertNull(parsed.fields["entity.email"])
    }

    @Test
    fun `скобки с метками — указание, скобки с текстом — текст`() {
        val parsed = parseFieldCandidates(
            "TRACK=20 4514 9154 9395 [w1 w2, w3 rule=track-shaped]\nADDRESS=Відділення №9 [нове]",
        )

        assertEquals(
            com.point.core.flow.FieldCandidate("20 4514 9154 9395", listOf("w1", "w2", "w3")),
            parsed.fields["entity.track"]!!.single(),
        )
        assertEquals(
            com.point.core.flow.FieldCandidate("Відділення №9 [нове]"),
            parsed.fields["entity.address"]!!.single(),
        )
    }

    // -- #261: кандидаты с уликами в одном вызове --

    @Test
    fun `кандидат с метками собирается со страницы — происхождение прочитано, улики есть`() = runTest {
        val result = realizer("TRACK=20 4514 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])
        assertEquals("ocr", meta["entity.track.src"])
        assertTrue(meta["entity.track.ev"]!!.split(",").size >= 2) // подтверждено, не предположение
    }

    @Test
    fun `диктовка без меток — происхождение модель и одно доказательство`() = runTest {
        val result = realizer("TRACK=99 9999 9999 9995").perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals("model", meta["entity.track.src"])
        assertEquals("semantic", meta["entity.track.ev"])
    }

    @Test
    fun `из двух кандидатов побеждает богатый уликами, оба остаются в alt`() = runTest {
        val result = realizer("TRACK=99 9999 9999 9995\nTRACK=20 4514 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])
        // Слитое значение — первым (конвенция .alt «победитель включён»), дальше все чтения.
        assertEquals(
            listOf("20 4514 9154 9395", "99 9999 9999 9995"),
            alternativesOf(meta, META_ENTITY_TRACK),
        )
    }

    // -- находки ревью #261: аннотации не лгут, отклонённое не тонет --

    /** Подтверждение моделью значения, прочитанного правилом, повышает доверие, а не снижает:
     *  происхождение не деградирует ocr→model, «возможно» не появляется (ревью #261). */
    @Test
    fun `подтверждение моделью не понижает происхождение и не судит без слоя`() = runTest {
        val known = textObject(
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + com.point.core.flow.META_SOURCE_SUFFIX to com.point.core.flow.SOURCE_OCR,
            ),
        )

        val result = realizer("TRACK=20 4514 9154 9395").perform(known) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals(com.point.core.flow.SOURCE_OCR, meta["entity.track.src"])
        assertNull("без слоя суд не состоялся — .ev не пишется", meta["entity.track.ev"])
    }

    @Test
    fun `отклонённое контрольной цифрой не исчезает — blocked виден`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456780UA")
            .perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertNull(meta[META_ENTITY_TRACK])
        val blocked = meta["entity.track" + com.point.core.flow.META_BLOCKED_SUFFIX]!!.split("\n")
        assertTrue(blocked.contains("RA123456789UA"))
        assertTrue("чтение повторного вызова тоже не тонет", blocked.contains("RA123456780UA"))
    }

    // -- #297: роли метками атомов — подпись вне указания, конфузаблы чинят имя --

    /** Дословный случай владельца («имена кривые»): OCR прочёл «1ваненко ван», модель указала
     *  метками на слова имени (подпись «Відправник» — вне указания) и починила буквы-жертвы. */
    @Test
    fun `роль метками атомов — имя без подписи, конфузаблы починены`() = runTest {
        val result = realizer("sender=Іваненко Іван [w6 w7]").perform(imageWithLayer()) as ActionResult.Success

        assertEquals("Іваненко Іван", result.result.metadata["graph.role.sender"])
    }

    /** Дым #297: без явной просьбы модель цитирует индекс дословно, и огрех OCR доезжает
     *  до экрана. Промпт ролей обязан просить писать имя правильно. */
    @Test
    fun `промпт ролей просит исправлять искажения распознавания в имени`() = runTest {
        realizer("sender=Іваненко Іван [w6 w7]").perform(imageWithLayer())

        assertTrue(lastPrompt!!.contains("исправляя явные искажения распознавания"))
        assertTrue(lastPrompt!!.contains("метки слов имени"))
    }

    /** Дым #297: модель включила метку подписи в указание, и значением стало «Вйдправник
     *  1ваненко ван». Подпись отрезает код — послушание модели механизмом не является. */
    @Test
    fun `метка подписи в указании отрезается кодом`() = runTest {
        val result = realizer("sender=Іваненко Іван [m2 w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Success

        assertEquals("Іваненко Іван", result.result.metadata["graph.role.sender"])
    }

    @Test
    fun `указание из одной подписи не отрезается в пустоту`() = runTest {
        val result = realizer("sender=Відправник [m2]").perform(imageWithLayer()) as ActionResult.Success

        assertEquals("Відправник", result.result.metadata["graph.role.sender"])
    }

    @Test
    fun `роль с галлюцинированными метками не пишется и не тратится`() = runTest {
        val result = realizer("sender=Хтось [z9]\nsender=Іваненко Іван [w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Success

        assertEquals("Іваненко Іван", result.result.metadata["graph.role.sender"])
    }

    @Test
    fun `переписанное целиком имя — страница побеждает, спор виден`() = runTest {
        val result = realizer("sender=Зовсім Інша Людина [w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals("1ваненко ван", meta["graph.role.sender"])
        assertTrue(alternativesOf(meta, "graph.role.sender").contains("Зовсім Інша Людина"))
    }

    @Test
    fun `при победе известного кандидаты модели не тонут`() = runTest {
        val known = textObject(metadata = mapOf("entity.phone" to "+380671234567"))

        val result = realizer("PHONE=+380679999999\nPHONE=+380671111111").perform(known) as ActionResult.Success

        assertEquals("+380671234567", result.result.metadata["entity.phone"])
        val alt = alternativesOf(result.result.metadata, "entity.phone")
        assertTrue(alt.contains("+380679999999"))
        assertTrue("второй кандидат не исчез", alt.contains("+380671111111"))
    }

    @Test
    fun `тронутая цифра — не ремонт, а второй кандидат, страница побеждает`() = runTest {
        val result = realizer("TRACK=20 4614 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Success

        val meta = result.result.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])
        assertTrue(alternativesOf(meta, META_ENTITY_TRACK).contains("20 4614 9154 9395"))
    }

    @Test
    fun `checksum S10 отклоняет кандидатов и даёт один повторный вызов`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456785UA [w1]")
            .perform(imageWithLayer()) as ActionResult.Success

        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("контрольной цифры"))
        assertEquals("RA123456785UA", result.result.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `повторный вызов один — второй провал не рождает третьего`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456780UA")
            .perform(imageWithLayer())

        assertEquals(2, prompts.size)
        assertTrue(result is ActionResult.Success) // SUMMARY выжил, действие не провалено
        assertNull((result as ActionResult.Success).result.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `индекс слов страницы приложен к запросу — правила размечают вход`() = runTest {
        realizer("TRACK=20 4514 9154 9395 [w1 w2 w3]").perform(imageWithLayer())

        assertTrue(lastPrompt!!.contains("[w2 rule=track-shaped]4514 9154"))
        assertTrue(lastPrompt!!.contains("квадратных скобках"))
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
