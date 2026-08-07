package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTest {

    private val card = ScratchRef("/scratch/card.jpg")

    private fun scannedCard() = PointObject(
        id = "card",
        mime = "image/jpeg",
        uri = card,
        state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_QR, Feature.HAS_TEXT)),
        metadata = mapOf(
            "entity.qr" to "https://example.org/vcard/17",
            META_OCR_TEXT_REF to "/scratch/card-ocr.txt",
        ),
    )

    private fun cloudReading(metadata: Map<String, String>) = PointObject(
        id = "fresh-uuid-from-store",
        mime = "image/jpeg",
        uri = card,
        state = ObjectState(ObjectKind.IMAGE),
        metadata = metadata,
    )

    private val cardFields = mapOf(
        "entity.phone" to "+380671234567",
        "entity.email" to "hello@example.org",
        "op" to "understand",
    )

    @Test
    fun `облачное чтение визитки не уносит с собой найденный локально QR`() {
        val known = scannedCard()
        val produced = cloudReading(cardFields)

        assertTrue("тот же файл — тот же объект", continuesObject(known, produced))
        val carried = carryKnowledge(known, produced)

        assertTrue("QR обязан пережить шаг", carried.state.has(Feature.HAS_QR))
        assertEquals("https://example.org/vcard/17", carried.metadata["entity.qr"])

        assertEquals("+380671234567", carried.metadata["entity.phone"])
    }

    @Test
    fun `ни один шаг не уменьшает набор известного — ни признака, ни значения`() {
        val known = scannedCard()
        val carried = carryKnowledge(known, cloudReading(cardFields))

        known.state.features.forEach { feature ->
            assertTrue("признак $feature потерян шагом", carried.state.has(feature))
        }
        known.metadata.forEach { (key, value) ->
            assertEquals("значение $key потеряно шагом", value, carried.metadata[key])
        }
    }

    @Test
    fun `спор двух чтений остаётся виден — наследование не стирает рассказ шага о нём`() {

        val known = scannedCard().let {
            it.copy(metadata = it.metadata + ("entity.address" to "вул. Сонячна, 15"))
        }
        val produced = cloudReading(
            cardFields + mapOf(
                "entity.address" to "вул. Сонячна, 15",
                "entity.address" + META_ALT_SUFFIX to altValue(listOf("вул. Сонячна, 15", "вул. Сонячна, 51")),
            ),
        )

        val carried = carryKnowledge(known, produced)

        assertEquals(
            listOf("вул. Сонячна, 15", "вул. Сонячна, 51"),
            alternativesOf(carried.metadata, "entity.address"),
        )
    }

    @Test
    fun `слово шага о своём ключе сильнее прежнего — иначе чтение не смогло бы починить огрех`() {
        val known = scannedCard().let {
            it.copy(metadata = it.metadata + ("entity.address" to "вул. Олексйвка, 3"))
        }
        val produced = cloudReading(cardFields + ("entity.address" to "вул. Олексіївка, 3"))

        assertEquals("вул. Олексіївка, 3", carryKnowledge(known, produced).metadata["entity.address"])
    }

    @Test
    fun `объект остаётся собой — за идентичность держатся связи графа и «Недавнее»`() {
        val carried = carryKnowledge(scannedCard(), cloudReading(cardFields))

        assertEquals("card", carried.id)
    }

    @Test
    fun `происхождение не понижается тем, что человек попросил прочитать ещё раз`() {
        val known = scannedCard().copy(provenance = Provenance.MODEL)

        assertEquals(Provenance.MODEL, carryKnowledge(known, cloudReading(cardFields)).provenance)
    }

    @Test
    fun `другой объект знания не наследует — чужие признаки на него не переносятся`() {
        val known = scannedCard()
        val extracted = PointObject(
            id = "track",
            mime = "text/plain",
            uri = ValueRef("20 4514 9154 9395"),
            state = ObjectState(ObjectKind.TEXT),
        )
        val converted = PointObject(
            id = "pdf",
            mime = "application/pdf",
            uri = ScratchRef("/scratch/card.pdf"),
            state = ObjectState(ObjectKind.PDF),
        )

        assertFalse(continuesObject(known, extracted))
        assertFalse(continuesObject(known, converted))
    }
}
