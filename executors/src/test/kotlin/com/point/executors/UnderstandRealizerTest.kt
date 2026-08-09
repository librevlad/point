package com.point.executors

import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ENTITY_GEO
import com.point.core.flow.META_ENTITY_METER
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.alternativesOf
import com.point.core.flow.layoutOf
import com.point.core.flow.parseFieldCandidates
import com.point.core.model.ActionResult
import com.point.core.model.Feature
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
import org.junit.Test
import java.io.File

class UnderstandRealizerTest {

    private val prompts = mutableListOf<String>()
    private val lastPrompt: String? get() = prompts.lastOrNull()
    private var lastLlmObject: PointObject? = null

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

    @Test
    fun `факты и роли выходят из одного вызова — стадии пайплайна больше не кнопки`() = runTest {
        val result = realizer("PHONE=+380671234567\nsender=P2\nSUMMARY=накладная на посылку")
            .perform(textObject())

        assertTrue(result is ActionResult.Done)
        val meta = (result as ActionResult.Done).findings!!.metadata
        assertEquals("+380671234567", meta["entity.phone"])

        assertEquals("ТОВ «Агротрейд»", meta["graph.role.sender"])
        assertEquals("накладная на посылку", meta["semantic.summary"])
    }

    @Test
    fun `понимание — знание о том же объекте, дубль не рождается`() = runTest {
        val result = realizer("PHONE=+380671234567").perform(textObject()) as ActionResult.Done

        assertTrue("знание не приносит новых объектов", result.findings!!.objects.isEmpty())
        assertEquals("+380671234567", result.findings!!.metadata["entity.phone"])
    }

    @Test
    fun `TRACK — законный ключ контракта, у идентификатора нет универсальной формы`() = runTest {

        val result = realizer("TRACK=RA123456785UA").perform(textObject()) as ActionResult.Done

        assertEquals("RA123456785UA", result.findings!!.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `METER и GEO — законные ключи контракта там, где правило формы слепо`() = runTest {

        val result = realizer("METER=00154\nGEO=50°27'0\"N 30°31'24\"E")
            .perform(textObject()) as ActionResult.Done

        assertEquals("00154", result.findings!!.metadata[META_ENTITY_METER])
        assertEquals("50°27'0\"N 30°31'24\"E", result.findings!!.metadata[META_ENTITY_GEO])
    }

    @Test
    fun `промпт называет новые ключи и требует показание без единицы`() {
        val prompt = understandPrompt(layoutOf(document))

        assertTrue(prompt.contains("METER"))
        assertTrue(prompt.contains("GEO"))
        assertTrue(prompt.contains("без единицы измерения"))
    }

    @Test
    fun `выдуманный идентификатор роли отброшен, факты того же ответа живут`() = runTest {
        val result = realizer("PHONE=+380671234567\nsender=P99").perform(textObject())

        val meta = (result as ActionResult.Done).findings!!.metadata
        assertEquals("+380671234567", meta["entity.phone"])
        assertNull(meta["graph.role.sender"])
    }

    @Test
    fun `нового не нашлось — это исход, а не отказ`() = runTest {
        val result = realizer("NONE").perform(textObject())

        assertTrue(result is ActionResult.Done)
        assertEquals("Point уже прочитал всё, что здесь есть", (result as ActionResult.Done).message)
    }

    @Test
    fun `проза вместо контракта не рождает ни фактов, ни ролей`() = runTest {
        val result = realizer("Конечно! Отправителем является ТОВ «Агротрейд».").perform(textObject())

        assertTrue(result is ActionResult.Done)
        assertNull("проза не имеет права стать фактом", (result as ActionResult.Done).findings)
    }

    @Test
    fun `пустой документ не доходит до модели`() = runTest {
        val result = realizer("PHONE=+380671234567").perform(textObject(content = "   "))

        assertTrue(result is ActionResult.Failure)
        assertNull("LLM не должен вызываться на пустом тексте", lastPrompt)
    }

    @Test
    fun `выдуманный моделью TYPE не сохраняется`() = runTest {
        val result = realizer("TYPE=POEM\nPHONE=+380671234567").perform(textObject()) as ActionResult.Done

        assertNull(result.findings!!.metadata["semantic.type"])
    }

    @Test
    fun `TYPE из закрытого списка становится semantic type`() = runTest {
        val result = realizer("TYPE=PURCHASE\nSUMMARY=чек із супермаркету").perform(textObject()) as ActionResult.Done

        assertEquals("purchase", result.findings!!.metadata["semantic.type"])
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
    fun `IBAN не становится трек-номером`() {

        // Живой прогон 2026-08-09: «✓ Отследить отправление UA7932…» — IBAN отправителя
        // с квитанции стал готовым действием. Форма IBAN — не трек.
        val parsed = parseFieldCandidates(
            "TRACK=UA793220010000026208373515609\nCARD=4149609057165427",
        )

        assertNull(parsed.fields["entity.track"])
        assertEquals("4149609057165427", parsed.fields["entity.card"]!!.single().text)
    }

    @Test
    fun `None от модели — отсутствие значения, а не значение`() {
        val parsed = parseFieldCandidates(
            "PHONE=None\nTRACK=null\nCARD=N/A\nAMOUNT=—\nPLACE=не найдено\n" +
                "RECEIPT=None [w1 w2]\nSUMMARY=None\nDATE=01.07.24",
        )

        assertEquals(setOf("entity.date"), parsed.fields.keys)
        assertNull("пустой итог не подписывает объект", parsed.single["semantic.summary"])
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

    @Test
    fun `кандидат с метками собирается со страницы — происхождение прочитано, улики есть`() = runTest {
        val result = realizer("TRACK=20 4514 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])
        assertEquals("ocr", meta["entity.track.src"])
        assertTrue(meta["entity.track.ev"]!!.split(",").size >= 2)
    }

    @Test
    fun `диктовка без меток — происхождение модель и одно доказательство`() = runTest {
        val result = realizer("TRACK=99 9999 9999 9995").perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("model", meta["entity.track.src"])
        assertEquals("semantic", meta["entity.track.ev"])
    }

    @Test
    fun `из двух кандидатов побеждает богатый уликами, оба остаются в alt`() = runTest {
        val result = realizer("TRACK=99 9999 9999 9995\nTRACK=20 4514 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])

        assertEquals(
            listOf("20 4514 9154 9395", "99 9999 9999 9995"),
            alternativesOf(meta, META_ENTITY_TRACK),
        )
    }

    @Test
    fun `подтверждение моделью не понижает происхождение и не судит без слоя`() = runTest {
        val known = textObject(
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.OCR.wire,
            ),
        )

        val result = realizer("TRACK=20 4514 9154 9395").perform(known) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals(Provenance.OCR.wire, meta["entity.track.src"])
        assertNull("без слоя суд не состоялся — .ev не пишется", meta["entity.track.ev"])
    }

    @Test
    fun `правку человека модель не понижает — ни в значении, ни в происхождении`() = runTest {
        val edited = textObject(
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
        )

        val result = realizer("TRACK=20 4514 9154 9395").perform(edited) as ActionResult.Done

        assertEquals(Provenance.HUMAN.wire, result.findings!!.metadata["entity.track.src"])
    }

    @Test
    fun `роль уходит в метаданные с происхождением, а не молча`() = runTest {
        val result = realizer("sender=Іваненко Іван [w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("Іваненко Іван", meta["graph.role.sender"])
        assertEquals(Provenance.MODEL.wire, meta["graph.role.sender.src"])
    }

    @Test
    fun `подтверждённую человеком роль модель не переписывает происхождением`() = runTest {
        val known = textObject(
            metadata = mapOf(
                "graph.role.sender" to "ТОВ «Агротрейд»",
                "graph.role.sender" + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
        )

        val result = realizer("sender=P2").perform(known) as ActionResult.Done

        assertEquals(Provenance.HUMAN.wire, result.findings!!.metadata["graph.role.sender.src"])
    }

    @Test
    fun `отклонённое контрольной цифрой не исчезает — blocked виден`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456780UA")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertNull(meta[META_ENTITY_TRACK])
        val blocked = meta["entity.track" + com.point.core.flow.META_BLOCKED_SUFFIX]!!.split("\n")
        assertTrue(blocked.contains("RA123456789UA"))
        assertTrue("чтение повторного вызова тоже не тонет", blocked.contains("RA123456780UA"))
    }

    @Test
    fun `промпт ролей просит исправлять искажения распознавания в имени`() = runTest {
        realizer("sender=Іваненко Іван [w6 w7]").perform(imageWithLayer())

        assertTrue(lastPrompt!!.contains("исправляя явные искажения распознавания"))
        assertTrue(lastPrompt!!.contains("метки слов имени"))
    }

    @Test
    fun `метка подписи в указании отрезается кодом`() = runTest {
        val result = realizer("sender=Іваненко Іван [m2 w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Done

        assertEquals("Іваненко Іван", result.findings!!.metadata["graph.role.sender"])
    }

    @Test
    fun `указание из одной подписи не отрезается в пустоту`() = runTest {
        val result = realizer("sender=Відправник [m2]").perform(imageWithLayer()) as ActionResult.Done

        assertEquals("Відправник", result.findings!!.metadata["graph.role.sender"])
    }

    @Test
    fun `роль с галлюцинированными метками не пишется и не тратится`() = runTest {
        val result = realizer("sender=Хтось [z9]\nsender=Іваненко Іван [w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Done

        assertEquals("Іваненко Іван", result.findings!!.metadata["graph.role.sender"])
    }

    @Test
    fun `переписанное целиком имя — страница побеждает, спор виден`() = runTest {
        val result = realizer("sender=Зовсім Інша Людина [w6 w7]")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("1ваненко ван", meta["graph.role.sender"])
        assertTrue(alternativesOf(meta, "graph.role.sender").contains("Зовсім Інша Людина"))
    }

    @Test
    fun `при победе известного кандидаты модели не тонут`() = runTest {

        // #652: телефоны — multi-value, другие номера живут «ещё»-значениями, не спором.
        val known = textObject(metadata = mapOf("entity.phone" to "+380671234567"))

        val result = realizer("PHONE=+380679999999\nPHONE=+380671111111").perform(known) as ActionResult.Done

        assertEquals("+380671234567", result.findings!!.metadata["entity.phone"])
        val more = com.point.core.flow.moreOf(result.findings!!.metadata, "entity.phone")
        assertTrue(more.contains("+380679999999"))
        assertTrue("второй кандидат не исчез", more.contains("+380671111111"))
        assertTrue(alternativesOf(result.findings!!.metadata, "entity.phone").isEmpty())
    }

    @Test
    fun `тронутая цифра — не ремонт, а второй кандидат, страница побеждает`() = runTest {
        val result = realizer("TRACK=20 4614 9154 9395 [w1 w2 w3]")
            .perform(imageWithLayer()) as ActionResult.Done

        val meta = result.findings!!.metadata
        assertEquals("20 4514 9154 9395", meta[META_ENTITY_TRACK])
        assertTrue(alternativesOf(meta, META_ENTITY_TRACK).contains("20 4614 9154 9395"))
    }

    @Test
    fun `checksum S10 отклоняет кандидатов и даёт один повторный вызов`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456785UA [w1]")
            .perform(imageWithLayer()) as ActionResult.Done

        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("контрольной цифры"))
        assertEquals("RA123456785UA", result.findings!!.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `повторный вызов один — второй провал не рождает третьего`() = runTest {
        val result = realizer("TRACK=RA123456789UA\nSUMMARY=лист", "TRACK=RA123456780UA")
            .perform(imageWithLayer())

        assertEquals(2, prompts.size)
        assertTrue(result is ActionResult.Done)
        assertNull((result as ActionResult.Done).findings!!.metadata[META_ENTITY_TRACK])
    }

    @Test
    fun `индекс слов страницы приложен к запросу — правила размечают вход`() = runTest {
        realizer("TRACK=20 4514 9154 9395 [w1 w2 w3]").perform(imageWithLayer())

        assertTrue(lastPrompt!!.contains("[w2 rule=track-shaped]4514 9154"))
        assertTrue(lastPrompt!!.contains("квадратных скобках"))
    }

    @Test
    fun `известный факт не затирается — другой номер виден «ещё»-значением`() = runTest {

        // #652: раньше расхождение лежало спором (.alt); телефон — multi-value,
        // второй номер — «ещё один», первый не тронут.
        val known = textObject(metadata = mapOf("entity.phone" to "+380671234567"))

        val result = realizer("PHONE=+380679999999").perform(known) as ActionResult.Done

        assertEquals("+380671234567", result.findings!!.metadata["entity.phone"])
        assertEquals(
            listOf("+380679999999"),
            com.point.core.flow.moreOf(result.findings!!.metadata, "entity.phone"),
        )
    }

    @Test
    fun `подтверждение первого трека не стирает второй номер страницы`() = runTest {
        val two = com.point.core.flow.altValue(listOf("20 4514 9154 9395", "20451491549396"))
        val known = textObject(
            metadata = mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + com.point.core.flow.META_MORE_SUFFIX to two,
            ),
        )

        val result = realizer("TRACK=20 4514 9154 9395").perform(known) as ActionResult.Done

        assertEquals("20 4514 9154 9395", result.findings!!.metadata[META_ENTITY_TRACK])
        assertEquals(
            listOf("20 4514 9154 9395", "20451491549396"),
            com.point.core.flow.moreOf(result.findings!!.metadata, META_ENTITY_TRACK),
        )
    }

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

    @Test
    fun `принимает текст и распознанную картинку, не сырое фото`() {
        val cap = UnderstandCapability(aiKeysReady)

        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `платное, медленное, сетевое — никогда не на первом экране`() {
        val meta = UnderstandCapability(aiKeysReady).meta

        assertTrue(meta.network)
        assertTrue(meta.auth)
        assertEquals(com.point.core.flow.Cost.PAID, meta.cost)
        assertEquals(com.point.core.flow.Latency.SLOW, meta.latency)
    }

    @Test
    fun `label — «Понять», produces — тот же state`() {
        val cap = UnderstandCapability(aiKeysReady)
        val state = ObjectState(ObjectKind.TEXT)

        assertEquals("Понять", cap.label(state))
        assertEquals(state, cap.produces(state))
    }

    @Test
    fun `картинка без текста читается глазами, а не отвергается`() = runTest {
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/meter.jpg"), ObjectState(ObjectKind.IMAGE),
        )

        val result = realizer("METER=20842\nSUMMARY=табло электросчётчика").perform(photo)

        assertTrue(result is ActionResult.Done)
        val meta = (result as ActionResult.Done).findings!!.metadata
        assertEquals("20842", meta["entity.meter"])
        assertEquals("табло электросчётчика", meta["semantic.summary"])
    }

    @Test
    fun `зрячее чтение помечает происхождение как модель и режим как рукопись`() = runTest {
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/meter.jpg"), ObjectState(ObjectKind.IMAGE),
        )

        val meta = (realizer("METER=154").perform(photo) as ActionResult.Done).findings!!.metadata

        assertEquals("model", meta["entity.meter.src"])
        assertEquals("HANDWRITTEN", meta["reading.mode"])
    }

    @Test
    fun `снимку отправляются пиксели, а не текстовая заглушка`() = runTest {
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/meter.jpg"), ObjectState(ObjectKind.IMAGE),
        )

        realizer("METER=154").perform(photo)

        assertEquals("image/jpeg", lastLlmObject!!.mime)
        assertTrue(lastPrompt!!.contains("Прочитай, что написано на снимке"))
    }

    @Test
    fun `на снимке нечего разобрать — честный отказ, а не пустой успех`() = runTest {
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/dark.jpg"), ObjectState(ObjectKind.IMAGE),
        )

        val result = realizer("NONE").perform(photo)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `слово человека не ремонтируется моделью при повторном понимании`() = kotlinx.coroutines.test.runTest {

        val human = textObject(
            metadata = mapOf(
                "entity.address" to "вул. Хрещатик, 1б",
                "entity.address" + com.point.core.flow.META_SOURCE_SUFFIX to
                    com.point.core.model.Provenance.HUMAN.wire,
            ),
        )

        // Модель предлагает «ремонтную» форму того же адреса — близкую, с совпадающими цифрами.
        val result = realizer("ADDRESS=вул. Хрещатик, 16").perform(human, null)

        val merged = (result as com.point.core.model.ActionResult.Done).findings!!.metadata
        org.junit.Assert.assertEquals("вул. Хрещатик, 1б", merged["entity.address"])
        org.junit.Assert.assertEquals(
            com.point.core.model.Provenance.HUMAN,
            com.point.core.flow.provenanceOf(merged, "entity.address"),
        )
    }

    @Test
    fun `пары имя-номер рождают подписанных людей, а номера — «ещё», не спор`() = runTest {

        // #653 (кейс 24): «в идеале я хочу 3 подписанных контакта, не или».
        val chat = textObject(
            "Начальник капітан АНДРІЯЩЕНКО Артур Миколайович +380 66 526 2706\n" +
                "сержант ДУМБРОВАН Олександр Миколайович +380 96 199 2869\n" +
                "сержант НОВІК Владислав Анатолійович +380 93 242 37 59",
        )

        val result = realizer(
            "CONTACT=+380 66 526 2706 | АНДРІЯЩЕНКО Артур Миколайович\n" +
                "CONTACT=+380 96 199 2869 | ДУМБРОВАН Олександр Миколайович\n" +
                "CONTACT=+380 93 242 37 59 | НОВІК Владислав Анатолійович\n" +
                "SUMMARY=Контакти служби",
        ).perform(chat, null)

        val findings = (result as ActionResult.Done).findings!!
        val people = findings.objects.filter { it.state.kind == com.point.core.flow.KIND_PERSON }
        assertEquals(3, people.size)

        val first = people.single { it.uri.value == "АНДРІЯЩЕНКО Артур Миколайович" }
        assertEquals("+380 66 526 2706", first.metadata["entity.phone"])
        assertTrue("человек умеет звонить", Feature.HAS_PHONE in first.state.features)
        assertTrue(findings.relations.any { it.fromId == first.id && it.toId == "doc" })

        val merged = findings.metadata
        assertTrue("номера — «ещё», не спор: " + merged["entity.phone.alt"],
            alternativesOf(merged, "entity.phone").isEmpty())
        assertEquals(2, com.point.core.flow.moreOf(merged, "entity.phone").size)
    }

    @Test
    fun `прежний спор телефонов переезжает в «ещё» при новом понимании`() = runTest {

        // #652: спор одного значения — не судьба второго телефона.
        val disputed = textObject(
            metadata = mapOf(
                "entity.phone" to "+380665262706",
                "entity.phone" + com.point.core.flow.META_ALT_SUFFIX to "+380961992869",
            ),
        )

        val result = realizer("PHONE=+380665262706\nPHONE=+380961992869").perform(disputed, null)

        val merged = (result as ActionResult.Done).findings!!.metadata
        assertTrue(alternativesOf(merged, "entity.phone").isEmpty())
        assertEquals(listOf("+380961992869"), com.point.core.flow.moreOf(merged, "entity.phone"))
    }
}
