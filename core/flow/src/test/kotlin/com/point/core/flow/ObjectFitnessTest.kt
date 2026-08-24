package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Годность — часть состояния объекта» (решение владельца, #684/#685): `Feature.UNUSABLE` +
 * `META_UNUSABLE_REASON` — один и тот же факт для экрана, подписи действия и Resolver'а,
 * а не проверка, разбросанная по каждому исполнителю.
 */
class ObjectFitnessTest {

    private fun obj(features: Set<Feature> = emptySet(), metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT, features), metadata)

    @Test
    fun `причина видна только когда объект и правда отмечен негодным`() {
        val marked = obj(setOf(Feature.UNUSABLE), mapOf(META_UNUSABLE_REASON to "Файл пустой"))

        assertEquals("Файл пустой", unusableReasonOf(marked.metadata))
    }

    @Test
    fun `пустая или пробельная причина не считается сказанной`() {
        assertNull(unusableReasonOf(emptyMap()))
        assertNull(unusableReasonOf(mapOf(META_UNUSABLE_REASON to "")))
        assertNull(unusableReasonOf(mapOf(META_UNUSABLE_REASON to "   ")))
    }

    @Test
    fun `GraphState отдаёт причину, только когда Feature действительно стоит`() {
        val withReasonButNoFeature = com.point.core.flow.GraphState(
            obj(emptySet(), mapOf(META_UNUSABLE_REASON to "Файл пустой")),
        )
        val marked = com.point.core.flow.GraphState(
            obj(setOf(Feature.UNUSABLE), mapOf(META_UNUSABLE_REASON to "Файл пустой")),
        )

        assertNull("знание без состояния — не считово", withReasonButNoFeature.unusableReason())
        assertEquals("Файл пустой", marked.unusableReason())
    }

    @Test
    fun `обычный объект без пометки не выдаёт причину`() {
        val ok = com.point.core.flow.GraphState(obj())

        assertNull(ok.unusableReason())
    }

    @Test
    fun `текст причины пустого файла — человеческий, без жаргона`() {
        assertTrue(EMPTY_FILE_REASON.isNotBlank())
        assertFalse(EMPTY_FILE_REASON.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    // ---- Негодному читать себя не предлагается (#994, #1101) ----

    private class Door(id: String, private val makes: (ObjectState) -> ObjectState?) : Capability {
        override val id = com.point.core.model.CapabilityId(id)
        override val icon = ""
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = makes(state)
    }

    /** Берёт объект как есть и отдаёт его дальше — намерение «отправить». */
    private val share = Door("share") { it }

    /** Спрашивает у модели: нового объекта не обещано — намерение «понять». */
    private val ai = Door("ai") { null }

    /** Читает содержимое и отдаёт текст — тоже «понять». */
    private val ocr = Door("ocr") { ObjectState(ObjectKind.TEXT) }

    /** Превращение: из снимка снимок — намерение «превратить», решения владельца не касается. */
    private val scan = Door("scan") { ObjectState(ObjectKind.IMAGE) }

    private val doors = listOf(ai, ocr, scan, share)

    private val unfit = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))
    private val fit = ObjectState(ObjectKind.IMAGE)

    /** Объекту сказано, что дело в его содержимом: «он повреждён или это не изображение». */
    private val brokenContent =
        mapOf(META_UNUSABLE_REASON to readerFailure(READER_NOT_DECODED, ObjectKind.IMAGE))

    @Test
    fun `негодному уходят двери чтения, а превращения и отправка остаются`() {
        assertEquals(listOf(scan, share), offeredWhenUnfit(unfit, brokenContent, doors))
    }

    @Test
    fun `годному — всё применимое, порядок тот же`() {
        assertEquals(doors, offeredWhenUnfit(fit, brokenContent, doors))
    }

    /**
     * Живой объект #1101: PDF под паролем. Предпросмотр срывается и метит объект негодным
     * без гарда `readerFailureIsFatal` (#1271), но человеку сказано «Прочитать сейчас не
     * вышло — попробуйте ещё раз». Отнять при этом ВСЕ чтения — оставить человека без того,
     * чем пробовать: ни «Прочитать документ», ни «Понять», ни «AI».
     */
    @Test
    fun `сорвавшаяся попытка чтения дверь чтения не закрывает`() {
        val attempt = mapOf(META_UNUSABLE_REASON to readerFailure("Password required", ObjectKind.PDF))

        assertEquals(doors, offeredWhenUnfit(unfit, attempt, doors))
        assertEquals(doors, offeredWhenUnfit(unfit, mapOf(META_UNUSABLE_REASON to READ_TOO_SLOW), doors))
        assertEquals(doors, offeredWhenUnfit(unfit, mapOf(META_UNUSABLE_REASON to READ_TOO_BIG), doors))
    }

    /** Метка без слов ничего не сказала: «не исследовано» — не «нечего читать». */
    @Test
    fun `метка молчит — двери чтения остаются`() {
        assertEquals(doors, offeredWhenUnfit(unfit, emptyMap(), doors))
        assertEquals(doors, offeredWhenUnfit(unfit, mapOf(META_UNUSABLE_REASON to " "), doors))
    }

    /** Пустой файл и обломок архива о содержимом сказали — чтения им не предлагаются. */
    @Test
    fun `пустому файлу и битому архиву чтения не предлагаются`() {
        val empty = mapOf(META_UNUSABLE_REASON to EMPTY_FILE_REASON)
        val archive = mapOf(META_UNUSABLE_REASON to BROKEN_ARCHIVE_REASON)

        assertEquals(listOf(scan, share), offeredWhenUnfit(unfit, empty, doors))
        assertEquals(listOf(scan, share), offeredWhenUnfit(unfit, archive, doors))
    }

    /**
     * Метку негодности ставит и сорвавшаяся операция — не отрисовался эскиз, не декодировался
     * снимок. Знание от этого не пропадает (Конституция §13): у снимка, чей QR прочитан,
     * дверь чтения обязана остаться на месте.
     */
    @Test
    fun `прочитанное старше метки — двери чтения остаются`() {
        val qr = investigationKey(com.point.core.model.CapabilityId("qr"))
        val answered = brokenContent +
            mapOf(qr to InvestigationState.FOUND.wire, "entity.url" to "https://point.app/x")

        assertEquals(doors, offeredWhenUnfit(unfit, answered, doors))
    }

    @Test
    fun `имя и размер — не рассказ объекта о себе`() {
        assertFalse(knowsFromContent(mapOf("name" to "bolshoy55.bin", "size" to "55000000")))
        assertFalse(knowsFromContent(mapOf(META_UNUSABLE_REASON to EMPTY_FILE_REASON)))
    }

    @Test
    fun `смотрели и не нашли — тоже не рассказ`() {
        val looked = investigationKey(com.point.core.model.CapabilityId("image-text"))

        assertFalse(knowsFromContent(mapOf(looked to InvestigationState.NOT_FOUND.wire)))
        assertTrue(knowsFromContent(mapOf(looked to InvestigationState.FOUND.wire)))
    }

    @Test
    fun `сущность и прочитанный текст — рассказ содержимого`() {
        assertTrue(knowsFromContent(mapOf("entity.url" to "https://point.app/x")))
        assertTrue(knowsFromContent(mapOf(META_OCR_ATOMS_REF to "/atoms.tsv")))
        assertFalse("пустое значение знанием не является", knowsFromContent(mapOf("entity.url" to "")))
    }
}
