package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Происхождение значения по метаданным и его подпись человеку (#264).
 *
 * Полюса словаря проверяются дословно: подпись — это то, что человек прочтёт на экране, и
 * менять её молча нельзя. Отдельно закреплено, что «возможно» **вычисляется** и означает ровно
 * две вещи, а не «число меньше единицы».
 */
class ValueProvenanceTest {

    private fun facts(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    // --- Чтение из метаданных ---

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

        assertEquals(Provenance.GIVEN, provenanceOf(meta, META_ENTITY_PREFIX + "phone"))
    }

    @Test
    fun `легаси-журнал без src не врёт — GIVEN и молчание вместо подписи`() {
        val meta = facts(META_ENTITY_TRACK to "20 4514 9154 9395")

        assertEquals(Provenance.GIVEN, provenanceOf(meta, META_ENTITY_TRACK))
        assertNull(provenanceLabel(provenanceOf(meta, META_ENTITY_TRACK)))
    }

    // --- Подписи человеку: каждое происхождение своим словом ---

    @Test
    fun `каждое происхождение подписано своим словом`() {
        assertEquals("прочитано", provenanceLabel(Provenance.OCR))
        assertEquals("выведено правилом", provenanceLabel(Provenance.RULE))
        assertEquals("прочитано моделью", provenanceLabel(Provenance.MODEL))
        assertEquals("подтверждено вами", provenanceLabel(Provenance.HUMAN))
    }

    @Test
    fun `принесённый человеком файл не подписывается — норма молчит`() {
        // Прецедент readingModeLabel(PRINTED) == null: подпись на всём подряд перестаёт
        // читаться там, где она важна.
        assertNull(provenanceLabel(Provenance.GIVEN))
        assertNull(readingModeLabel(ReadingMode.PRINTED))
    }

    @Test
    fun `у каждого происхождения подпись своя — два слота не сливаются в одно слово`() {
        val labels = Provenance.entries.mapNotNull(::provenanceLabel)

        assertEquals(labels.size, labels.distinct().size)
    }

    // --- Ключ факта узла ---

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

    // --- «Возможно» вычисляется, а не наследуется от числа ---

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
    fun `один победитель в alt спором не является — конвенция «победитель включён»`() {
        val alone = facts(
            META_ENTITY_PREFIX + "address" to "вул. Хрещатик, 1",
            META_ENTITY_PREFIX + "address" + META_ALT_SUFFIX to altValue(listOf("вул. Хрещатик, 1")),
        )

        assertFalse(isDisputed(alone, META_ENTITY_PREFIX + "address"))
        assertFalse(isDoubtful(alone))
    }

    @Test
    fun `происхождение и сомнение — разные вещи, и «прочитано моделью» не значит «возможно»`() {
        // Дословный прецедент #264: перевозчик, названный моделью, получал 0.7 → «возможно».
        // Модель — происхождение; сомнение — это улики и спор, и здесь их нет.
        val role = facts(
            META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта",
            META_GRAPH_ROLE_PREFIX + "carrier" + META_SOURCE_SUFFIX to Provenance.MODEL.wire,
        )

        assertEquals("прочитано моделью", provenanceLabel(provenanceOf(role, META_GRAPH_ROLE_PREFIX + "carrier")))
        assertFalse("«возможно» не наследуется от происхождения", isDoubtful(role))
    }

    // --- Один суд на два экрана ---

    @Test
    fun `карточка готовности и найденный объект судят предположение одной функцией`() {
        val judged = trackFacts("ТТН 20 4514 9154 9395 прибула")
        val ready = ACTION_SCHEMAS.single { it.id == "track-parcel" }.readiness(judged) as Readiness.Ready

        assertEquals(
            ready.present.single { it.spec.critical }.assumption,
            isAssumption(judged, META_ENTITY_TRACK),
        )
    }

    // --- Персист: происхождение переживает журнал в метаданных ---

    @Test
    fun `происхождение переживает журнал — оно живёт в метаданных, а не в поле объекта`() {
        // Объекты графа не журналируются вовсе: их пересобирают энричеры из метаданных, а
        // метаданные журналируются целиком. Значит round-trip карты и есть весь персист.
        val written = trackFacts("ТТН 20 4514 9154 9395 прибула")
        val restored = written.toList().toMap() // как из FileFlowSnapshotStore: ключ → строка

        assertEquals(Provenance.OCR, provenanceOf(restored, META_ENTITY_TRACK))
        assertTrue(isAssumption(restored, META_ENTITY_TRACK))
    }

    @Test
    fun `слияние фактов не понижает происхождение — human переживает ответ модели`() {
        // #243 через общий контракт: аннотации не голосуются, а штампует их только тот, кто
        // сильнее ([UnderstandRealizer]). Здесь проверяется нижняя половина — mergeFacts.
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
