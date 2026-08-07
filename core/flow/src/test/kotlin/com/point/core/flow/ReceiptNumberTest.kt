package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptNumberTest {

    @Test
    fun `номер читается рядом со словом «квитанція»`() {
        val facts = receiptFacts("Квитанція № AB12-CD34-EF56-GH78 від 26.04.2026")

        assertEquals("AB12-CD34-EF56-GH78", facts[META_ENTITY_RECEIPT])
    }

    @Test
    fun `порченое движком слово — то же слово`() {

        assertTrue(looksLikeReceiptMarker("Квитанщя"))
        assertEquals(
            "AB12-CD34-EF56-GH78",
            receiptFacts("Квитанщя № AB12-CD34-EF56-GH78 Big 26.04.2026")[META_ENTITY_RECEIPT],
        )

        assertTrue(receiptNumbers("Довдка № AB12-CD34-EF56-GH78").isEmpty())
    }

    @Test
    fun `без слова рядом номер не читается`() {

        assertTrue(receiptNumbers("AB12-CD34-EF56-GH78").isEmpty())
    }

    @Test
    fun `слово без номера ничего не рождает`() {
        assertTrue(receiptNumbers("Квитанція ОРИГІНАЛ").isEmpty())
        assertTrue(receiptNumbers("Квитанція № 12").isEmpty())
    }

    @Test
    fun `ссылка на квитанцию номером не становится`() {

        assertTrue(receiptNumbers("Посилання на квитанцію check.bank.example/p/NaXzzIgeffgJA").isEmpty())
    }

    @Test
    fun `дата рядом со словом номером не становится`() {

        assertEquals(
            listOf("AB12-CD34-EF56-GH78"),
            receiptNumbers("Квитанція № AB12-CD34-EF56-GH78 від 26.04.2026"),
        )
    }

    @Test
    fun `цифровой номер рядом со словом читается`() {
        assertEquals("950333221", receiptNumbers("Receipt 950333221").single())
    }

    @Test
    fun `улика ровно одна, происхождение — прочитано`() {
        val facts = receiptFacts("Квитанція № AB12-CD34-EF56-GH78")

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_RECEIPT + META_SOURCE_SUFFIX])
        assertEquals("semantic", facts[META_ENTITY_RECEIPT + META_EVIDENCE_SUFFIX])
    }

    @Test
    fun `чужая страница номеров квитанций не рождает`() {
        assertTrue(receiptFacts("ТТН 20 4514 9154 9395 прибула до відділення").isEmpty())
        assertTrue(receiptFacts("Показання 20842 кВт·ч").isEmpty())
        assertTrue(receiptFacts("").isEmpty())
    }

    @Test
    fun `форму номера судит одна функция — и правило, и чтение модели`() {
        assertEquals(true, semanticFits(META_ENTITY_RECEIPT, "AB12-CD34-EF56-GH78"))
        assertEquals(false, semanticFits(META_ENTITY_RECEIPT, "ОРИГІНАЛ"))
    }

    @Test
    fun `маркеры квитанции на слое и в плоском тексте не расходятся`() {

        FIELD_MARKERS.getValue(META_ENTITY_RECEIPT).forEach {
            assertTrue("«$it» не узнан стемами плоского текста", looksLikeReceiptMarker(it))
        }
    }
}
