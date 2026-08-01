package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voting on a value rather than on a table cell (#222, шаг 7).
 *
 * The mechanics come straight out of [reconcile] and are covered there too; what is new is that
 * they now apply wherever two sources read the same thing — and that a disagreement is *kept*
 * instead of resolved into a value that looks as settled as any other.
 */
class AgreementTest {

    @Test
    fun `nothing read is not a disagreement`() {
        assertNull(agree(emptyList()))
        assertNull(agree(listOf("", "   ")))
    }

    @Test
    fun `one reading is agreement with itself`() {
        val v = agree(listOf("вул. Хрещатик, 1"))!!

        assertEquals("вул. Хрещатик, 1", v.value)
        assertTrue(v.agreed)
        assertTrue(v.candidates.isEmpty())
    }

    @Test
    fun `format noise is not a disagreement`() {
        // Case, spacing, dashes and punctuation fold away — otherwise every source would
        // "disagree" with every other and the flag would mean nothing.
        val v = agree(listOf("+380 67 123-45-67", "+380671234567", "+380 67 123 45 67"))!!

        assertTrue(v.agreed)
        assertEquals("+380 67 123-45-67", v.value) // the first raw form, never a normalised one
    }

    @Test
    fun `two of three agreeing wins the vote, and the loser is kept`() {
        val v = agree(listOf("Хрещатик 1", "Хрещатик 7", "Хрещатик, 1"))!!

        assertEquals("Хрещатик 1", v.value)
        assertFalse(v.agreed)
        assertEquals(listOf("Хрещатик 1", "Хрещатик 7", "Хрещатик, 1"), v.candidates)
    }

    @Test
    fun `three sources all disagreeing gives a flag and three candidates`() {
        val v = agree(listOf("A", "B", "C"))!!

        assertFalse(v.agreed)
        assertEquals(3, v.candidates.size)
    }

    @Test
    fun `on a tie the first reading wins, so the caller controls precedence by order`() {
        assertEquals("известное", agree(listOf("известное", "свежее"))!!.value)
        assertEquals("свежее", agree(listOf("свежее", "известное"))!!.value)
    }

    // --- Маркер ⚠ против голосования (ревью #258) ---

    @Test
    fun `ячейка из одних маркеров — не разобрано, а не чтение — слово страницы побеждает`() {
        val v = agree(listOf("⚠", "20"))!!

        assertEquals("20", v.value)
        assertTrue(v.agreed) // «не разобрано» — отсутствие, не разногласие
        assertTrue(v.candidates.isEmpty()) // и в дропдаун «⚠» вариантом не лезет
    }

    @Test
    fun `все источники не разобрали — маркер выживает, а не тихая пустота`() {
        assertEquals("⚠", agree(listOf("⚠", "⚠"))!!.value)
    }

    @Test
    fun `чистое чтение бьёт помеченное в согласной группе независимо от порядка`() {
        // Пометка выживает, только если ни один источник не подтвердил значение чистым, —
        // иначе судьбу ⚠ решал бы порядок, в котором источники успели ответить.
        assertEquals("1600", agree(listOf("1600⚠", "1600"))!!.value)
        assertEquals("1600", agree(listOf("1600", "1600⚠"))!!.value)
        assertEquals("1600⚠", agree(listOf("1600⚠", "1600⚠"))!!.value)
    }

    // --- Merging what is known with what just arrived ---

    @Test
    fun `a fact nobody knew is simply taken`() {
        val merged = mergeFacts(emptyMap(), mapOf("entity.phone" to "+380671234567"))

        assertEquals("+380671234567", merged["entity.phone"])
        assertTrue(alternativesOf(merged, "entity.phone").isEmpty())
    }

    @Test
    fun `sources that agree leave no trace of a dispute`() {
        val merged = mergeFacts(
            mapOf("entity.phone" to "+380 67 123 45 67"),
            mapOf("entity.phone" to "+380671234567"),
        )

        assertEquals("+380 67 123 45 67", merged["entity.phone"])
        assertTrue(alternativesOf(merged, "entity.phone").isEmpty())
    }

    @Test
    fun `a later source no longer wins by simply arriving second`() {
        // This is the behaviour that changed: deep-understand used to overwrite the on-device
        // reading outright, and nothing recorded that the two had ever differed.
        val merged = mergeFacts(
            mapOf("entity.address" to "вул. Хрещатик, 1"),
            mapOf("entity.address" to "вул. Хрещатик, 7"),
        )

        assertEquals("вул. Хрещатик, 1", merged["entity.address"])
        assertEquals(
            listOf("вул. Хрещатик, 1", "вул. Хрещатик, 7"),
            alternativesOf(merged, "entity.address"),
        )
    }

    @Test
    fun `agreeing later clears an earlier dispute`() {
        val disputed = mergeFacts(mapOf("entity.address" to "A"), mapOf("entity.address" to "B"))

        val settled = mergeFacts(disputed, mapOf("entity.address" to "A"))

        assertEquals("A", settled["entity.address"])
        assertTrue(alternativesOf(settled, "entity.address").isEmpty())
    }

    @Test
    fun `a third disagreeing source adds to the candidates, it does not replace them`() {
        val first = mergeFacts(mapOf("entity.address" to "A"), mapOf("entity.address" to "B"))

        val second = mergeFacts(first, mapOf("entity.address" to "C"))

        assertEquals(listOf("A", "B", "C"), alternativesOf(second, "entity.address"))
    }

    @Test
    fun `stored alternatives are never themselves merged as facts`() {
        val merged = mergeFacts(
            mapOf("entity.address" to "A"),
            mapOf("entity.address$META_ALT_SUFFIX" to "мусор"),
        )

        assertEquals("A", merged["entity.address"])
        assertTrue(alternativesOf(merged, "entity.address").isEmpty())
    }

    @Test
    fun `other facts are carried through untouched`() {
        val merged = mergeFacts(
            mapOf("name" to "чек.jpg", "entity.phone" to "+380671234567"),
            mapOf("entity.date" to "завтра"),
        )

        assertEquals("чек.jpg", merged["name"])
        assertEquals("+380671234567", merged["entity.phone"])
        assertEquals("завтра", merged["entity.date"])
    }

    @Test
    fun `the stored form survives a round trip through one separator`() {
        // One place knows the separator: a platform line separator would make the journal
        // unreadable on the paired PC.
        val stored = mapOf("k$META_ALT_SUFFIX" to altValue(listOf("A", "B", "C")))

        assertEquals(listOf("A", "B", "C"), alternativesOf(stored, "k"))
    }

    // --- Починка искажений распознавания (#236) ---

    @Test
    fun `restoring the letter OCR ate is a repair, not a disagreement`() {
        // Ровно случай владельца: «Олексіївка» распозналась как «Олексйвка».
        assertTrue(isRepairOf("Олексйвка, вул. Сонячна, 15", "Олексіївка, вул. Сонячна, 15"))
    }

    @Test
    fun `a different street is not a repair`() {
        assertFalse(isRepairOf("Олексйвка, вул. Сонячна, 15", "Київ, вул. Хрещатик, 1"))
    }

    @Test
    fun `a changed digit is never a repair`() {
        // Телефон, ошибшийся на цифру, — другой человек; накладная — другая посылка;
        // «Хрещатик, 1» против «Хрещатик, 7» — другое здание в получасе езды.
        assertFalse(isRepairOf("+380671234567", "+380671234568"))
        assertFalse(isRepairOf("20451491549395", "20451491549396"))
        assertFalse(isRepairOf("вул. Хрещатик, 1", "вул. Хрещатик, 7"))
        assertFalse(isRepairOf("вул. Хрещатик, 15", "вул. Хрещатик, 155"))
    }

    @Test
    fun `letters may be repaired while the digits stay exactly as they were`() {
        assertTrue(isRepairOf("Олексйвка, вул. Сонячна, 15", "Олексіївка, вул. Сонячна, 15"))
        assertTrue(isRepairOf("вул. Хрещатк, 15", "вул. Хрещатик, 15"))
    }

    @Test
    fun `a short value is not repairable — the bound would mean nothing`() {
        assertFalse(isRepairOf("Київ", "Кіїв"))
    }

    // --- Конфузаблы OCR (#297): буква-жертва чинится, числа неприкосновенны ---

    @Test
    fun `цифра-конфузабл в буквенном слове — жертва OCR, ремонт разрешён`() {
        // Живой случай владельца («имена кривые»): «Іваненко Іван» прочитан как «1ваненко ван».
        assertTrue(isRepairOf("1ваненко ван", "Іваненко Іван"))
        assertTrue(isRepairOf("Петренко 0лена", "Петренко Олена"))
    }

    @Test
    fun `цифра числового токена — identity, даже одинокая`() {
        assertFalse(isRepairOf("вул. Сонячна, 1", "вул. Сонячна, 7"))
        assertFalse(isRepairOf("Відділення №9, Хрещатик", "Відділення №8, Хрещатик"))
    }

    @Test
    fun `цифра в буквенном слове без конфузабла или в другую цифру — спор, не ремонт`() {
        assertFalse(isRepairOf("будинок Дом7 корпус", "будинок Дом9 корпус"))
        assertFalse(isRepairOf("будинок Дом1 корпус", "будинок Дом2 корпус"))
    }

    @Test
    fun `the same value is not a repair of itself`() {
        assertFalse(isRepairOf("вул. Сонячна, 15", "вул. Сонячна, 15"))
        assertFalse(isRepairOf("вул. Сонячна, 15", "ВУЛ. СОНЯЧНА, 15"))
    }

    @Test
    fun `a repair wins the value and leaves no dispute behind`() {
        val merged = mergeFacts(
            mapOf("entity.address" to "Олексйвка, вул. Сонячна, 15"),
            mapOf("entity.address" to "Олексіївка, вул. Сонячна, 15"),
        )

        assertEquals("Олексіївка, вул. Сонячна, 15", merged["entity.address"])
        assertTrue(alternativesOf(merged, "entity.address").isEmpty())
    }

    @Test
    fun `a repair clears a dispute recorded earlier`() {
        val disputed = mergeFacts(
            mapOf("entity.address" to "Олексйвка, вул. Сонячна, 15"),
            mapOf("entity.address" to "совсем другое место"),
        )
        assertTrue(alternativesOf(disputed, "entity.address").isNotEmpty())

        val repaired = mergeFacts(disputed, mapOf("entity.address" to "Олексіївка, вул. Сонячна, 15"))

        assertEquals("Олексіївка, вул. Сонячна, 15", repaired["entity.address"])
        assertTrue(alternativesOf(repaired, "entity.address").isEmpty())
    }

    @Test
    fun `something far away is still a disagreement, not a repair`() {
        val merged = mergeFacts(
            mapOf("entity.address" to "Олексйвка, вул. Сонячна, 15"),
            mapOf("entity.address" to "Київ, проспект Перемоги, 100"),
        )

        assertEquals("Олексйвка, вул. Сонячна, 15", merged["entity.address"])
        assertEquals(2, alternativesOf(merged, "entity.address").size)
    }

    @Test
    fun `a phone the model rewrote is a disagreement the user gets to see`() {
        val merged = mergeFacts(
            mapOf("entity.phone" to "+380671234567"),
            mapOf("entity.phone" to "+380671234568"),
        )

        assertEquals("+380671234567", merged["entity.phone"])
        assertEquals(2, alternativesOf(merged, "entity.phone").size)
    }

    @Test
    fun `цифра против другой похожей цифры — спор, а не ремонт (ревью #297)`() {
        // Свёртка конфузаблов складывала обе стороны сразу, и «1» с «3» исчезали вместе:
        // identity дома решало расстояние Левенштейна. Живые адреса из находки ревью.
        assertFalse(isRepairOf("будинок Дом1 корпус", "будинок Дом3 корпус"))
        assertFalse(isRepairOf("просп.Перемоги,37", "просп.Перемоги,17"))
        assertFalse(isRepairOf("вул.Шевченка,10", "вул.Шевченка,13"))
        assertFalse(isRepairOf("Договір№1030 від", "Договір№3030 від"))
    }

    @Test
    fun `цифра-жертва по-прежнему чинится, когда встречное чтение даёт букву`() {
        assertTrue(isRepairOf("1ваненко ван", "Іваненко Іван"))
        assertTrue(isRepairOf("Петренко 0лена", "Петренко Олена"))
        // Цифры в другом токене при этом не мешают ремонту имени.
        assertTrue(isRepairOf("1ваненко буд.10", "Іваненко буд.10"))
    }

    @Test
    fun `появление или пропажа похожей цифры — тоже спор`() {
        assertFalse(isRepairOf("будинок Дом корпус", "будинок Дом1 корпус"))
        assertFalse(isRepairOf("вул.Сонячна, буд.1", "вул.Сонячна, буд.10"))
    }
}
