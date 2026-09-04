package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueProvenanceTest {

    private fun facts(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `происхождение читается из src своего ключа`() {
        val meta = facts(
            META_ENTITY_TRACK to "20 4514 9154 9395",
            META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.OCR.wire,
        )

        assertEquals(Provenance.OCR, provenanceOf(meta, META_ENTITY_TRACK))
    }

    @Test
    fun `чужой src на соседнем ключе не приписывается этому значению`() {
        val meta = facts(
            META_ENTITY_PREFIX + "phone" to "+380671112233",
            META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
        )

        assertEquals(Provenance.UNKNOWN, provenanceOf(meta, META_ENTITY_PREFIX + "phone"))
    }

    @Test
    /**
     * Знание без `.src` — «неизвестно», а не «дано» (#948): и молчит, и галочки не получает.
     */
    fun `легаси-журнал без src не врёт — «неизвестно» и молчание вместо подписи`() {
        val meta = facts(META_ENTITY_TRACK to "20 4514 9154 9395")

        assertEquals(Provenance.UNKNOWN, provenanceOf(meta, META_ENTITY_TRACK))
        assertNull(provenanceLabel(provenanceOf(meta, META_ENTITY_TRACK)))
        assertFalse("неизвестное получило галочку", isKnownFor(meta, META_ENTITY_TRACK))
    }

    @Test
    fun `названное происхождение галочку получает`() {
        val meta = facts(
            META_ENTITY_TRACK to "20 4514 9154 9395",
            META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.OCR.wire,
        )

        assertTrue(isKnownFor(meta, META_ENTITY_TRACK))
    }

    @Test
    fun `каждое происхождение подписано своим словом`() {
        assertEquals("прочитано", provenanceLabel(Provenance.OCR))
        assertEquals("выведено правилом", provenanceLabel(Provenance.RULE))
        assertEquals("понято по смыслу", provenanceLabel(Provenance.MODEL))
        assertEquals("подтверждено вами", provenanceLabel(Provenance.HUMAN))
    }

    @Test
    fun `принесённый человеком файл не подписывается — норма молчит`() {

        assertNull(provenanceLabel(Provenance.GIVEN))
    }

    @Test
    fun `у каждого происхождения подпись своя — два слота не сливаются в одно слово`() {
        val labels = Provenance.entries.mapNotNull(::provenanceLabel)

        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `ключ факта узла — один, аннотации не в счёт`() {
        val node = facts(
            META_ENTITY_TRACK to "20 4514 9154 9395",
            META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.OCR.wire,
            META_ENTITY_TRACK + META_EVIDENCE_SUFFIX to "semantic",
        )

        assertEquals(META_ENTITY_TRACK, factKeyOf(node))
    }

    @Test
    fun `у объекта без собственного факта судить нечего`() {
        assertNull(factKeyOf(emptyMap()))
        assertFalse(isDoubtful(emptyMap()))
    }

    @Test
    fun `одна улика — предположение, две независимые — подтверждение`() {
        val one = facts(
            META_ENTITY_TRACK to "A",
            META_ENTITY_TRACK + META_EVIDENCE_SUFFIX to "semantic",
        )
        val two = facts(
            META_ENTITY_TRACK to "A",
            META_ENTITY_TRACK + META_EVIDENCE_SUFFIX to "semantic,geometric",
        )

        assertTrue(isAssumption(one, META_ENTITY_TRACK))
        assertTrue(isDoubtful(one))
        assertFalse(isAssumption(two, META_ENTITY_TRACK))
        assertFalse(isDoubtful(two))
    }

    @Test
    fun `улик не считали вовсе — не судили, и врать нельзя ни в одну сторону`() {
        val unjudged = facts(META_ENTITY_TRACK to "A")

        assertFalse(isAssumption(unjudged, META_ENTITY_TRACK))
        assertFalse(isDoubtful(unjudged))
    }

    @Test
    fun `спор источников — «возможно», согласие — нет`() {
        val disputed = facts(
            META_ENTITY_PREFIX + "address" to "вул. Хрещатик, 1",
            META_ENTITY_PREFIX + "address" + META_ALT_SUFFIX to
                altValue(listOf("вул. Хрещатик, 1", "вул. Хрещатик, 7")),
        )

        assertTrue(isDisputed(disputed, META_ENTITY_PREFIX + "address"))
        assertTrue(isDoubtful(disputed))
    }

    @Test
    fun `тот же день двумя написаниями — согласие, а не спор прочтений`() {

        // #1436, живая охота 04.09.2026: «Meet on September 11, 2026 … deadline 11 September 2026».
        // Один день записан двумя способами — это не спор, объекту нельзя приписывать «спорят».
        val dateKey = META_ENTITY_PREFIX + "date"
        val sameDay = facts(
            dateKey to "September 11, 2026",
            dateKey + META_ALT_SUFFIX to altValue(listOf("11 September 2026")),
        )

        assertFalse("два написания одного дня выданы за спор", isDisputed(sameDay, dateKey))
        assertFalse(isDoubtful(sameDay))
    }

    @Test
    fun `тот же номер в разной записи — согласие, а не спор`() {
        val phoneKey = META_ENTITY_PREFIX + "phone"
        val sameNumber = facts(
            phoneKey to "+380671234567",
            phoneKey + META_ALT_SUFFIX to altValue(listOf("067 123 4567")),
        )

        assertFalse("один номер в двух записях выдан за спор", isDisputed(sameNumber, phoneKey))
    }

    @Test
    fun `разные дни — настоящий спор, его не глушим`() {
        val dateKey = META_ENTITY_PREFIX + "date"
        val twoDays = facts(
            dateKey to "16.04.2026",
            dateKey + META_ALT_SUFFIX to altValue(listOf("18.04.2026")),
        )

        assertTrue("настоящий спор дней перестал считаться спором", isDisputed(twoDays, dateKey))
        assertTrue(isDoubtful(twoDays))
    }

    @Test
    fun `один победитель в alt спором не является — конвенция «победитель включён»`() {
        val alone = facts(
            META_ENTITY_PREFIX + "address" to "вул. Хрещатик, 1",
            META_ENTITY_PREFIX + "address" + META_ALT_SUFFIX to altValue(listOf("вул. Хрещатик, 1")),
        )

        assertFalse(isDisputed(alone, META_ENTITY_PREFIX + "address"))
        assertFalse(isDoubtful(alone))
    }

    @Test
    fun `происхождение и сомнение — разные вещи, и «понято по смыслу» не значит «возможно»`() {

        val role = facts(
            META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта",
            META_GRAPH_ROLE_PREFIX + "carrier" + META_SOURCE_SUFFIX to Provenance.MODEL.wire,
        )

        assertEquals("понято по смыслу", provenanceLabel(provenanceOf(role, META_GRAPH_ROLE_PREFIX + "carrier")))
        assertFalse("«возможно» не наследуется от происхождения", isDoubtful(role))
    }

    @Test
    fun `карточка готовности и найденный объект судят предположение одной функцией`() {
        val judged = trackFacts("ТТН 20 4514 9154 9395 прибула")
        val ready = ACTION_SCHEMAS.single { it.id == "track-parcel" }.readiness(judged) as Readiness.Ready

        assertEquals(
            ready.present.single { it.spec.critical }.assumption,
            isAssumption(judged, META_ENTITY_TRACK),
        )
    }

    @Test
    fun `происхождение переживает журнал — оно живёт в метаданных, а не в поле объекта`() {

        val written = trackFacts("ТТН 20 4514 9154 9395 прибула")
        val restored = written.toList().toMap()

        assertEquals(Provenance.OCR, provenanceOf(restored, META_ENTITY_TRACK))
        assertTrue(isAssumption(restored, META_ENTITY_TRACK))
    }

    @Test
    fun `слияние фактов не понижает происхождение — human переживает ответ модели`() {

        val merged = mergeFacts(
            mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
            ),
            mapOf(META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.MODEL.wire),
        )

        assertEquals(Provenance.HUMAN, provenanceOf(merged, META_ENTITY_TRACK))
    }
}
