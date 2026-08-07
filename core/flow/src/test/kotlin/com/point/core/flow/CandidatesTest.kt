package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatesTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    private val layer = AtomLayer(
        listOf(
            atom("m1", "ТТН", 10f, 100f, 60f, 120f),
            atom("a1", "20", 200f, 100f, 230f, 120f),
            atom("a2", "4514 9154", 235f, 100f, 330f, 120f),
            atom("a3", "9395", 335f, 100f, 380f, 120f),
            atom("far", "Одержувач", 10f, 300f, 150f, 320f),
        ),
    )

    private val track = FieldCandidate("20 4514 9154 9395", listOf("a1", "a2", "a3"))

    @Test
    fun `указанный трек с подписью рядом собирает три класса — подтверждён`() {
        val ev = layer.fieldEvidence(META_ENTITY_TRACK, track)

        assertTrue(EvidenceClass.SEMANTIC in ev)
        assertTrue(EvidenceClass.STRUCTURAL in ev)
        assertTrue(EvidenceClass.GEOMETRIC in ev)
        assertTrue(ev.size >= CONFIRMED_CLASSES)
    }

    @Test
    fun `диктовка без меток может заработать только форму — предположение`() {
        val ev = layer.fieldEvidence(META_ENTITY_TRACK, FieldCandidate("20 4514 9154 9395"))

        assertEquals(setOf(EvidenceClass.SEMANTIC), ev)
        assertTrue(ev.size < CONFIRMED_CLASSES)
    }

    @Test
    fun `галлюцинированная метка рушит структурный класс`() {
        val ev = layer.fieldEvidence(
            META_ENTITY_TRACK,
            FieldCandidate("20 4514 9154 9395", listOf("a1", "ghost", "a2", "a3")),
        )

        assertFalse(EvidenceClass.STRUCTURAL in ev)
    }

    @Test
    fun `маркер в том же пробеге — лексический класс, не геометрический`() {

        val glued = AtomLayer(
            listOf(
                atom("m", "ТТН", 10f, 100f, 60f, 120f),
                atom("v", "20451491549395", 70f, 100f, 250f, 120f),
            ),
        )

        val ev = glued.fieldEvidence(META_ENTITY_TRACK, FieldCandidate("20451491549395", listOf("v")))

        assertTrue(EvidenceClass.LEXICAL in ev)
        assertFalse(EvidenceClass.GEOMETRIC in ev)
    }

    @Test
    fun `подпись строкой выше над значением — геометрический класс`() {
        val stacked = AtomLayer(
            listOf(
                atom("m", "Телефон", 100f, 60f, 220f, 80f),
                atom("v", "+380671234567", 90f, 100f, 280f, 120f),
            ),
        )

        val ev = stacked.fieldEvidence(META_ENTITY_PREFIX + "phone", FieldCandidate("+380671234567", listOf("v")))

        assertTrue(EvidenceClass.GEOMETRIC in ev)
    }

    @Test
    fun `метки с процитированным атрибутом rule не теряют улик`() {
        val ev = layer.fieldEvidence(
            META_ENTITY_TRACK,
            FieldCandidate("20 4514 9154 9395", listOf("a1 rule=track-shaped", "a2 rule=track-shaped", "a3 rule=track-shaped")),
        )

        assertTrue(EvidenceClass.STRUCTURAL in ev)
    }

    @Test
    fun `маркер в дальнем пробеге той же строки — не подпись`() {

        val far = AtomLayer(
            listOf(
                atom("m", "ТТН", 10f, 100f, 60f, 120f),
                atom("x", "інше", 200f, 100f, 260f, 120f),
                atom("v", "20451491549395", 500f, 100f, 700f, 120f),
            ),
        )

        val ev = far.fieldEvidence(META_ENTITY_TRACK, FieldCandidate("20451491549395", listOf("v")))

        assertFalse(EvidenceClass.GEOMETRIC in ev)
    }

    @Test
    fun `заголовок через пустые полстраницы — не подпись над значением`() {
        val distant = AtomLayer(
            listOf(
                atom("m", "Телефон", 100f, 60f, 220f, 80f),
                atom("v", "+380671234567", 90f, 600f, 280f, 620f),
            ),
        )

        val ev = distant.fieldEvidence(META_ENTITY_PREFIX + "phone", FieldCandidate("+380671234567", listOf("v")))

        assertFalse(EvidenceClass.GEOMETRIC in ev)
    }

    @Test
    fun `формы полей — свидетели`() {
        assertTrue(semanticFits(META_ENTITY_TRACK, "20 4514 9154 9395") == true)
        assertTrue(semanticFits(META_ENTITY_TRACK, "RA123456789UA") == true)

        assertTrue(semanticFits(META_ENTITY_TRACK, "RA 123456785 UA") == true)
        assertTrue(semanticFits(META_ENTITY_PREFIX + "phone", "+380 67 123 45 67") == true)
        assertTrue(semanticFits(META_ENTITY_PREFIX + "phone", "1600") == false)
        assertTrue(semanticFits(META_ENTITY_PREFIX + "email", "olena@example.com") == true)

        assertTrue(semanticFits(META_ENTITY_PREFIX + "address", "вул. Хрещатик, 1") == true)

        assertNull(
            "неузнанная запись адреса — не провал формы",
            semanticFits(META_ENTITY_PREFIX + "address", "м. Павлоград, вул. Кодацька, 39, Днiпропетровська обл."),
        )
    }

    @Test
    fun `контрольная цифра S10 считается по стандарту UPU`() {

        assertEquals(true, s10CheckDigitValid("RA123456785UA"))
        assertEquals(false, s10CheckDigitValid("RA123456789UA"))
        assertEquals(true, s10CheckDigitValid("ra 12345678 5 ua"))
    }

    @Test
    fun `у 14-значного номера checksum неприменима, а не провалена`() {
        assertNull(s10CheckDigitValid("20 4514 9154 9395"))
        assertNull(s10CheckDigitValid("случайный текст"))
    }

    @Test
    fun `улики без страницы — форма и контрольная цифра, одной функцией на обоих судей`() {

        assertEquals(
            setOf(EvidenceClass.SEMANTIC, EvidenceClass.ARITHMETIC),
            formEvidence(META_ENTITY_TRACK, "RA123456785UA"),
        )
        assertEquals(setOf(EvidenceClass.SEMANTIC), formEvidence(META_ENTITY_TRACK, "20 4514 9154 9395"))
        assertTrue(formEvidence(META_ENTITY_TRACK, "не номер").isEmpty())
    }

    @Test
    fun `сошедшаяся контрольная цифра — второй класс и на слое страницы`() {
        val s10 = AtomLayer(listOf(atom("v", "RA123456785UA", 10f, 100f, 190f, 120f)))

        val ev = s10.fieldEvidence(META_ENTITY_TRACK, FieldCandidate("RA123456785UA", listOf("v")))

        assertTrue(EvidenceClass.ARITHMETIC in ev)
    }

    @Test
    fun `контрольная цифра отправления чужому полю класса не даёт`() {

        listOf("phone", "card", "date", "address").forEach { field ->
            assertTrue(
                "«$field» не должен получать ARITHMETIC от трековой контрольной цифры",
                formEvidence(META_ENTITY_PREFIX + field, "RA123456785UA").isEmpty(),
            )
        }
        val layer = AtomLayer(listOf(atom("v", "RA123456785UA", 10f, 100f, 190f, 120f)))
        val ev = layer.fieldEvidence(
            META_ENTITY_PREFIX + "date",
            FieldCandidate("RA123456785UA", listOf("v")),
        )
        assertTrue(EvidenceClass.ARITHMETIC !in ev)
    }

    @Test
    fun `mergeFacts не сливает аннотации как факты`() {
        val merged = mergeFacts(
            mapOf("entity.track" to "A", "entity.track.src" to com.point.core.model.Provenance.OCR.wire),
            mapOf(
                "entity.track.src" to com.point.core.model.Provenance.MODEL.wire,
                "entity.track.ev" to "semantic",
            ),
        )

        assertEquals(com.point.core.model.Provenance.OCR.wire, merged["entity.track.src"])
        assertNull(merged["entity.track.ev"])
    }

    @Test
    fun `словарь аннотаций полон`() {
        listOf(".alt", ".more", ".ev", ".src").forEach {
            assertTrue(isAnnotationKey("entity.track$it"))
        }
        assertFalse(isAnnotationKey("entity.track"))
    }
}
