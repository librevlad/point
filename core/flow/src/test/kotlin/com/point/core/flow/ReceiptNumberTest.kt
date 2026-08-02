package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Номер квитанции — факт, заведённый вместе со схемой «Переслать квитанцию» (#262).
 *
 * Формы сняты глазами с кадра 20 корпуса (лист банка): «Квитанція № AB12-CD34-EF56-GH78 від
 * 26.04.2026» — номер буквенно-цифровой, блоками через дефис, и опознаётся он не формой, а
 * стоящим рядом словом.
 */
class ReceiptNumberTest {

    @Test
    fun `номер читается рядом со словом «квитанція»`() {
        val facts = receiptFacts("Квитанція № AB12-CD34-EF56-GH78 від 26.04.2026")

        assertEquals("AB12-CD34-EF56-GH78", facts[META_ENTITY_RECEIPT])
    }

    @Test
    fun `без слова рядом номер не читается`() {
        // Одной формы мало по построению: `AB12-CD34-EF56-GH78` неотличим от артикула.
        assertTrue(receiptNumbers("AB12-CD34-EF56-GH78").isEmpty())
    }

    @Test
    fun `слово без номера ничего не рождает`() {
        assertTrue(receiptNumbers("Квитанція ОРИГІНАЛ").isEmpty())
        assertTrue(receiptNumbers("Квитанція № 12").isEmpty())
    }

    @Test
    fun `ссылка на квитанцию номером не становится`() {
        // Кадр 08: «Посилання на квитанцію check.bank.example/p/NaXzz…». Квитанция там за
        // ссылкой, и читает её другое поле схемы — сама ссылка, а не выдуманный «номер».
        assertTrue(receiptNumbers("Посилання на квитанцію check.bank.example/p/NaXzzIgeffgJA").isEmpty())
    }

    @Test
    fun `дата рядом со словом номером не становится`() {
        // «26.04.2026» стоит в той же строке, что и маркер, но номером не является: её куски
        // короче порога, и это ровно та граница, ради которой порог существует.
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
        // Два судьи одного слова — словарь атомного слоя и стемы плоского текста. Разъедутся —
        // «квитанція рядом» станет значить разное на скриншоте и в тексте (тот же тест у трека).
        FIELD_MARKERS.getValue(META_ENTITY_RECEIPT).forEach {
            assertTrue("«$it» не узнан стемами плоского текста", looksLikeReceiptMarker(it))
        }
    }
}
