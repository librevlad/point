package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единая семантика merge — ADR-0001 §15, RFC §19.
 *
 * Раньше знание приходило двумя путями с разными правилами: `mergeFacts` хранил расхождение,
 * а путь обогащения выбрасывал новое значение, если ключ уже был занят.
 */
class MergeKnowledgeTest {

    private val phone = "entity.phone"

    /** Два прочтения одного снимка: то, за которым человек пошёл сам, и офлайн-чтение (#1242). */
    private val strongText = "/scratch/сильное.txt"

    private val offlineText = "/scratch/офлайн.txt"

    /** Два обычных прочтения того же документа на той стороне: первое и перечитывание (#1242). */
    private val pcText = "/pc/первое.txt"

    private val pcReread = "/pc/второе.txt"

    @Test
    fun `two sources reading the same value agree without alternatives`() {
        val known = mapOf(phone to "+380671234567")
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234567"))

        assertEquals("+380671234567", merged[phone])
        assertEquals(emptyList<String>(), alternativesOf(merged, phone))
    }

    @Test
    fun `two sources reading different values keep both as a conflict`() {
        val known = mapOf(phone to "+380671234567")
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234599"))

        val kept = alternativesOf(merged, phone) + merged.getValue(phone)
        assertTrue("+380671234567 потерян-$kept", kept.contains("+380671234567"))
        assertTrue("+380671234599 потерян-$kept", kept.contains("+380671234599"))
    }

    @Test
    fun `a second reading is never dropped just because the key is already taken`() {
        val known = mapOf("entity.track" to "20 4514 9154 9395")
        val merged = mergeKnowledge(known, mapOf("entity.track" to "20 4514 9154 9999"))

        assertTrue(
            "новое прочтение исчезло молча",
            merged.getValue("entity.track") == "20 4514 9154 9999" ||
                alternativesOf(merged, "entity.track").contains("20 4514 9154 9999"),
        )
    }

    @Test
    fun `provenance climbs to the stronger source and never falls back`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire)
        val merged = mergeKnowledge(known, mapOf(phone + META_SOURCE_SUFFIX to Provenance.HUMAN.wire))

        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))

        val back = mergeKnowledge(merged, mapOf(phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))
        assertEquals(Provenance.HUMAN, provenanceOf(back, phone))
    }

    @Test
    fun `evidence is kept and only replaced by a better grounded one`() {
        val known = mapOf(phone to "+380671234567", phone + META_EVIDENCE_SUFFIX to "semantic,lexical")
        val merged = mergeKnowledge(known, mapOf(phone + META_EVIDENCE_SUFFIX to "semantic"))
        assertEquals("semantic,lexical", merged[phone + META_EVIDENCE_SUFFIX])

        val richer = mergeKnowledge(merged, mapOf(phone + META_EVIDENCE_SUFFIX to "semantic,lexical,structural"))
        assertEquals("semantic,lexical,structural", richer[phone + META_EVIDENCE_SUFFIX])
    }

    @Test
    fun `alternatives from both sides are united, not replaced`() {
        val known = mapOf(phone to "A", phone + META_ALT_SUFFIX to altValue(listOf("A", "B")))
        val merged = mergeKnowledge(known, mapOf(phone + META_ALT_SUFFIX to altValue(listOf("C"))))

        assertEquals(listOf("A", "B", "C"), alternativesOf(merged, phone))
    }

    @Test
    fun `repeating a known reading does not erase the stored conflict`() {
        val known = mapOf(phone to "A", phone + META_ALT_SUFFIX to altValue(listOf("A", "B")))
        val merged = mergeKnowledge(known, mapOf(phone to "A"))

        assertEquals(listOf("A", "B"), alternativesOf(merged, phone))
    }

    @Test
    fun `refreshable references are replaced, not reconciled`() {
        val known = mapOf(META_OCR_TEXT_REF to "/scratch/old.txt")
        val merged = mergeKnowledge(
            known,
            mapOf(META_OCR_TEXT_REF to "/scratch/new.txt"),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("/scratch/new.txt", merged[META_OCR_TEXT_REF])
        assertEquals(emptyList<String>(), alternativesOf(merged, META_OCR_TEXT_REF))
    }

    /**
     * #1242: человек нажал «Прочитать сильнее», облако ответило за секунды — а начатое до
     * того офлайн-чтение дошло позже и молча встало поверх сильного прочтения.
     */
    @Test
    fun `позднее слабое прочтение не замещает сильное, а остаётся расхождением (#1242)`() {
        val strong = mapOf(
            META_OCR_TEXT_REF to strongText,
            META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
        )

        val merged = mergeKnowledge(
            strong,
            mapOf(META_OCR_TEXT_REF to offlineText),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("сильное прочтение подменили поздним слабым", strongText, merged[META_OCR_TEXT_REF])
        assertEquals(
            "второе прочтение пропало вместо того, чтобы остаться расхождением",
            listOf(offlineText),
            alternativesOf(merged, META_OCR_TEXT_REF),
        )
        assertTrue("сила прочтения потеряна", readStrongly(merged, META_OCR_TEXT_REF))
    }

    @Test
    fun `сильное прочтение замещает прежнее — за ним человек и пошёл (#1242)`() {
        val known = mapOf(META_OCR_TEXT_REF to offlineText)

        val merged = mergeKnowledge(
            known,
            mapOf(
                META_OCR_TEXT_REF to strongText,
                META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
            ),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("прочтение, за которым пошёл человек, не стало главным", strongText, merged[META_OCR_TEXT_REF])
        assertTrue("сила прочтения не записана", readStrongly(merged, META_OCR_TEXT_REF))
    }

    /**
     * #1242: пометка силы доезжала до объекта без самого прочтения — из кадра-родителя, из
     * записи «Недавнего». Защищать было нечего, а первое же чтение объекта она отправляла в
     * «или»: у страницы не оставалось текста вовсе.
     */
    @Test
    fun `пометка силы без прочтения не запирает первое чтение объекта (#1242)`() {
        val orphan = mapOf(META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG)

        val merged = mergeKnowledge(
            orphan,
            mapOf(META_OCR_TEXT_REF to offlineText),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("у объекта не осталось прочтения вовсе", offlineText, merged[META_OCR_TEXT_REF])
        assertEquals(emptyList<String>(), alternativesOf(merged, META_OCR_TEXT_REF))
    }

    /**
     * #1242: человек прочитал страницу сильнее и отправил её на компьютер, а файл с текстом
     * на телефоне уже не читается. Ссылка в дорогу не едет — а пометка при ней ехала, и на
     * компьютере ложная подпись «здесь прочитано сильнее» оставалась навсегда: снять её
     * нечем, и второе обычное перечитывание уходило в «или» вместо замены.
     */
    @Test
    fun `мёртвая ссылка не увозит на компьютер пометку силы (#1242)`() {
        val known = mapOf(
            META_OCR_TEXT_REF to strongText,
            META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
        )

        val packed = knowledgePackedForTravel(known, text = null)
        val firstReadingOnPc = mergeKnowledge(
            packed,
            mapOf(META_OCR_TEXT_REF to pcText),
            refreshable = setOf(META_OCR_TEXT_REF),
        )
        val reread = mergeKnowledge(
            firstReadingOnPc,
            mapOf(META_OCR_TEXT_REF to pcReread),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("на компьютер уехала подпись без своего прочтения", emptyMap<String, String>(), packed)
        assertEquals("перечитывание на компьютере не обновило текст", pcReread, reread[META_OCR_TEXT_REF])
    }

    /**
     * #1242: файл с прочитанным текстом лежит у самого человека, и он вправе его убрать. Ссылка
     * тогда уже ничего не свидетельствует — и пометка при ней тоже: без неё перечитывание того
     * же документа на этом же устройстве уходило в «или» вместо замены.
     */
    @Test
    fun `потерянный файл текста уносит и пометку силы (#1242)`() {
        val obj = PointObject(
            "id",
            "image/jpeg",
            ScratchRef("/scratch/страница.jpg"),
            ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(
                META_OCR_TEXT_REF to strongText,
                META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
            ),
        )

        val forgotten = knowledgeOfReadText(obj) { false }
        val reread = mergeKnowledge(
            forgotten.metadata,
            mapOf(META_OCR_TEXT_REF to offlineText),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("пометка осталась без своего прочтения", emptyMap<String, String>(), forgotten.metadata)
        assertEquals("перечитывание не обновило текст", offlineText, reread[META_OCR_TEXT_REF])
    }

    /**
     * #1242: текст приехал на компьютер, но лечь файлом там не смог. Знания о прочтении на
     * той стороне нет — и подписи «прочитано сильнее» при нём тоже: иначе объект на
     * компьютере навсегда оставался с чужой силой поверх пустого места.
     */
    @Test
    fun `не легший на той стороне текст не оставляет пометку силы (#1242)`() {
        val packed = knowledgePackedForTravel(
            mapOf(
                META_OCR_TEXT_REF to strongText,
                META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
            ),
            text = "Гречка 2 кг",
        )

        val landed = knowledgeArrivedFromTravel(packed, ref = null)
        val readOnPc = mergeKnowledge(
            landed.metadata,
            mapOf(META_OCR_TEXT_REF to pcText),
            refreshable = setOf(META_OCR_TEXT_REF),
        )
        val reread = mergeKnowledge(
            readOnPc,
            mapOf(META_OCR_TEXT_REF to pcReread),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("подпись осталась без всякого текста", emptyMap<String, String>(), landed.metadata)
        assertEquals("перечитывание на компьютере не обновило текст", pcReread, reread[META_OCR_TEXT_REF])
    }

    /** Ключ знания уходит вместе с пометками при нём — сиротам взяться неоткуда (#1242). */
    @Test
    fun `знание уносится с пометками при нём, а не одними значениями (#1242)`() {
        val known = mapOf(
            META_OCR_TEXT_REF to strongText,
            META_OCR_TEXT_REF + META_STRENGTH_SUFFIX to READING_STRONG,
            phone to "+380671234567",
        )

        val left = withoutKnowledge(known, setOf(META_OCR_TEXT_REF))

        assertEquals(mapOf(phone to "+380671234567"), left)
    }

    @Test
    fun `сила прочтения — не находка и не строка на экран (#1242)`() {
        assertTrue(isAnnotationKey(META_OCR_TEXT_REF + META_STRENGTH_SUFFIX))
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(emptyMap(), listOf(META_OCR_TEXT_REF + META_STRENGTH_SUFFIX)),
        )
    }

    @Test
    fun `investigation state is a state, so the fresh one wins instead of becoming a conflict`() {
        val qr = CapabilityId("qr")
        val known = withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND)
        val merged = mergeKnowledge(known, withInvestigation(emptyMap(), qr, InvestigationState.FOUND))

        assertEquals(InvestigationState.FOUND, investigationStateOf(merged, qr))
        assertEquals(emptyList<String>(), alternativesOf(merged, investigationKey(qr)))
    }

    // ---- Этап 5: человек как источник знания (ADR §8, RFC §19) ----

    private fun human(key: String, value: String) =
        mapOf(key to value, key + META_SOURCE_SUFFIX to Provenance.HUMAN.wire)

    @Test
    fun `human correction becomes primary and keeps the machine reading as history`() {
        val known = mapOf(phone to "111", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, human(phone, "112"))

        assertEquals("112", merged[phone])
        assertEquals(listOf("111"), alternativesOf(merged, phone))
        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))
        assertTrue("исправление человеком — не спор", !isDisputed(merged, phone))
        assertTrue(!isDoubtful(merged.filterKeys { it.startsWith(phone) }))
    }

    @Test
    fun `human value survives later model ocr and rule readings`() {
        var m = mergeKnowledge(mapOf(phone to "111"), human(phone, "112"))

        m = mergeKnowledge(m, mapOf(phone to "113", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))
        m = mergeKnowledge(m, mapOf(phone to "114", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire))
        m = mergeKnowledge(m, mapOf(phone to "115", phone + META_SOURCE_SUFFIX to Provenance.RULE.wire))

        assertEquals("человеческое значение не вытесняется", "112", m[phone])
        assertEquals(Provenance.HUMAN, provenanceOf(m, phone))
        assertTrue("машинные чтения не пропадают", alternativesOf(m, phone).containsAll(listOf("111")))
    }

    @Test
    fun `a machine repair-shaped reading cannot overwrite the human value`() {

        val known = mergeKnowledge(mapOf(phone to "вул. Сонячна 15"), human(phone, "вул. Сонячна 15б"))
        val m = mergeKnowledge(known, mapOf(phone to "вул. Сонячна 156"))

        assertEquals("вул. Сонячна 15б", m[phone])
        assertEquals(Provenance.HUMAN, provenanceOf(m, phone))
    }

    @Test
    fun `human confirmation keeps the value and adds no artificial conflict`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, human(phone, "+380671234567"))

        assertEquals("+380671234567", merged[phone])
        assertEquals(emptyList<String>(), alternativesOf(merged, phone))
        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))
        assertTrue(!isDoubtful(merged.filterKeys { it.startsWith(phone) }))
    }

    @Test
    fun `machine versus machine conflict semantics are untouched by the human rule`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234599", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))

        val kept = alternativesOf(merged, phone) + merged.getValue(phone)
        assertTrue(kept.containsAll(listOf("+380671234567", "+380671234599")))
        assertTrue("машинный спор остаётся спором", isDisputed(merged, phone))
    }

    @Test
    fun `a newer human word replaces the older human word, keeping it in history`() {
        val first = mergeKnowledge(emptyMap(), human(phone, "112"))
        val second = mergeKnowledge(first, human(phone, "119"))

        assertEquals("119", second[phone])
        assertTrue(alternativesOf(second, phone).contains("112"))
        assertEquals(Provenance.HUMAN, provenanceOf(second, phone))
    }
}
