package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterReadingTest {

    @Test
    fun `показание света становится фактом с происхождением «прочитано» и одной уликой формы`() {

        assertEquals(
            mapOf(
                META_ENTITY_METER to "20842",
                META_ENTITY_METER_UNIT to "кВт·ч",
                META_ENTITY_METER + META_SOURCE_SUFFIX to Provenance.OCR.wire,
                META_ENTITY_METER + META_EVIDENCE_SUFFIX to "semantic",
            ),
            meterFacts("Показання лічильника 20842 кВт·ч"),
        )
    }

    @Test
    fun `вода читается тем же правилом — три цифры кадра 09`() {
        val facts = meterFacts("Вода 154 м³ за червень")

        assertEquals("154", facts[META_ENTITY_METER])
        assertEquals("м³", facts[META_ENTITY_METER_UNIT])
    }

    @Test
    fun `единица пишется дословно, как на странице — квт-год и m3 тоже счётчики`() {
        assertEquals("кВт.год", meterFacts("Спожито 1250 кВт.год")[META_ENTITY_METER_UNIT])
        assertEquals("m3", meterFacts("Water 00154 m3")[META_ENTITY_METER_UNIT])
        assertEquals("Гкал", meterFacts("Тепло 105,3 Гкал")[META_ENTITY_METER_UNIT])
    }

    @Test
    fun `дробная часть показания не отрезается`() {
        assertEquals("105,3", meterFacts("Тепло 105,3 Гкал")[META_ENTITY_METER])
    }

    @Test
    fun `случайное число показанием не становится — единица и есть причина верить`() {

        assertTrue(meterFacts("Сума до сплати 20842 грн").isEmpty())
        assertTrue(meterFacts("ТТН 20 4514 9154 9395 прибула").isEmpty())
        assertTrue(meterFacts("Особовий рахунок 305412").isEmpty())
    }

    @Test
    fun `объём в прозе — не показание табло`() {

        assertTrue(meterReadings("залито 2 м³ бетону").isEmpty())
        assertTrue(meterReadings("спожито 12 м3 за місяць").isEmpty())
    }

    @Test
    fun `длинный номер рядом с единицей — номер счётчика, а не обрезок показания`() {

        assertTrue(meterReadings("лічильник №20842123456 кВт·ч").isEmpty())
    }

    @Test
    fun `разрядный пробел — часть показания, а не его граница`() {

        assertEquals("20 842", meterFacts("Спожито 20 842 кВт·ч")[META_ENTITY_METER])
        assertEquals("20 842,5", meterFacts("Показання 20 842,5 кВт·ч")[META_ENTITY_METER])
    }

    @Test
    fun `разрядный пробел не ссорит правило с моделью — пробел складывается`() {

        assertEquals(normConsensus("20842"), normConsensus(meterFacts("Спожито 20 842 кВт·ч")[META_ENTITY_METER]!!))
        assertEquals(true, semanticFits(META_ENTITY_METER, "20 842"))
    }

    @Test
    fun `разрядные группы — ровно по три цифры, иначе это соседние числа`() {

        assertEquals("1 234 567", meterReadings("Спожито 1 234 567 м³").single().value)
        assertEquals("5678", meterReadings("1234 5678 м³").single().value)
    }

    @Test
    fun `разрядное число длиннее табло не режется до последней тройки`() {

        assertTrue(meterReadings("лічильник 123 456 789 кВт·ч").isEmpty())
    }

    @Test
    fun `два счётчика на странице — второе показание не прячется`() {
        val facts = meterFacts("День 20842 кВт·ч\nНіч 10514 кВт·ч")

        assertEquals("20842", facts[META_ENTITY_METER])
        assertEquals(listOf("20842", "10514"), moreOf(facts, META_ENTITY_METER))
        assertTrue(alternativesOf(facts, META_ENTITY_METER).isEmpty())
    }

    @Test
    fun `одно показание, напечатанное дважды, остаётся одним`() {
        assertEquals(1, meterReadings("20842 кВт·ч ... повторно 20842 кВт·ч").size)
    }

    @Test
    fun `одно число с двух счётчиков не спорит само с собой в строке «или»`() {
        val facts = meterFacts("Холодна 154 м³\nГаряча 154 м3")

        assertEquals("154", facts[META_ENTITY_METER])
        assertTrue("одно значение — не «второе показание»", moreOf(facts, META_ENTITY_METER).isEmpty())
    }

    @Test
    fun `единица кричащими буквами — та же единица`() {

        assertEquals("20842", meterFacts("ПОКАЗАННЯ 20842 КВТ·Ч")[META_ENTITY_METER])
    }

    @Test
    fun `нет показания — нет ключей, а не ключ с пустотой`() {
        assertTrue(meterFacts("Позвони на +380671234567").isEmpty())
        assertTrue(meterFacts("").isEmpty())
    }

    @Test
    fun `форма показания судится одной реализацией — правилом и валидатором кандидатов`() {

        assertEquals(true, semanticFits(META_ENTITY_METER, "20842"))
        assertEquals(false, semanticFits(META_ENTITY_METER, "12"))
    }

    @Test
    fun `ведущие нули барабана остаются в значении дословно`() {

        val facts = meterFacts("Показання 00001154 м³")

        assertEquals("00001154", facts[META_ENTITY_METER])
        assertEquals("м³", facts[META_ENTITY_METER_UNIT])
    }

    @Test
    fun `подсказка не становится фактом — в метаданных живёт только дословное`() {

        val facts = meterFacts("Показання 00001154 м³")

        assertTrue("подсказки в метаданных быть не должно", facts.none { it.value == "1154" })
        assertEquals(
            setOf(
                META_ENTITY_METER,
                META_ENTITY_METER_UNIT,
                META_ENTITY_METER + META_SOURCE_SUFFIX,
                META_ENTITY_METER + META_EVIDENCE_SUFFIX,
            ),
            facts.keys,
        )
    }

    @Test
    fun `живые показания устройства теряют в подсказке ровно ведущие нули`() {
        assertEquals("1154", meterWithoutDrumZeros("00001154"))
        assertEquals("7145", meterWithoutDrumZeros("007145"))
        assertEquals("208425", meterWithoutDrumZeros("0208425"))
    }

    @Test
    fun `всё значащее — подсказывать нечего, и карточка молчит`() {

        assertNull(meterWithoutDrumZeros("20842"))
        assertNull(meterWithoutDrumZeros("154"))
        assertNull(meterWithoutDrumZeros("20 842"))
        assertNull(meterWithoutDrumZeros("105,3"))
    }

    @Test
    fun `единственный ноль уже без ведущих нулей — подсказка совпала бы со значением`() {
        assertNull(meterWithoutDrumZeros("0"))
        assertNull(meterWithoutDrumZeros("0,5"))
    }

    @Test
    fun `барабан из одних нулей — это ноль, а не пустота`() {

        assertEquals("0", meterWithoutDrumZeros("000"))
        assertEquals("0", meterWithoutDrumZeros("00000000"))
        assertEquals("0,5", meterWithoutDrumZeros("00,5"))
    }

    @Test
    fun `дробная часть и разрядные пробелы подсказку не ломают`() {
        assertEquals("154,3", meterWithoutDrumZeros("00154,3"))
        assertEquals("154", meterWithoutDrumZeros("000 154"))
    }

    @Test
    fun `не число — подсказки нет, Point не советует передать слово`() {

        assertNull(meterWithoutDrumZeros("0 показань"))
        assertNull(meterWithoutDrumZeros("0 куб.м знято"))

        assertNull(fieldHint(META_ENTITY_METER, "00154 м³"))
    }

    @Test
    fun `пробелы по краям подсказку не выдумывают`() {

        assertNull(meterWithoutDrumZeros(" 0 "))
        assertEquals("1154", meterWithoutDrumZeros(" 00001154 "))
    }

    @Test
    fun `подсказка есть только у показания — чужой ведущий ноль часть номера`() {

        assertNull(fieldHint(META_ENTITY_TRACK, "0420459154939512"))
        assertNull(fieldHint(META_ENTITY_PREFIX + "phone", "0671234567"))
        assertEquals("1154", fieldHint(META_ENTITY_METER, "00001154"))
    }

    @Test
    fun `карточка получает оба числа — дословное со страницы и подсказку рядом`() {
        val facts = meterFacts("Показання 00001154 м³")
        val ready = ACTION_SCHEMAS.single { it.id == "meter-reading" }.readiness(facts)
        val field = (ready as Readiness.Ready).present.single { it.spec.critical }

        assertEquals("00001154", field.value)
        assertEquals("1154", field.hint)
    }

    @Test
    fun `показание без нулей подсказки в карточке не заводит`() {
        val ready = ACTION_SCHEMAS.single { it.id == "meter-reading" }
            .readiness(meterFacts("Показання 20842 кВт·ч"))

        assertNull((ready as Readiness.Ready).present.single { it.spec.critical }.hint)
    }

    @Test
    fun `показание с одной уликой видно как предположение, а не как факт`() {
        val facts = meterFacts("Показання 20842 кВт·ч")
        val ready = ACTION_SCHEMAS.single { it.id == "meter-reading" }.readiness(facts)

        assertTrue(ready is Readiness.Ready)
        assertTrue(
            "одна улика — предположение, и оно обязано быть видно как предположение",
            (ready as Readiness.Ready).present.single { it.spec.critical }.assumption,
        )
    }

    @Test
    fun `перенос строки разрядным пробелом не бывает — время сверху в показание не приклеивается`() {

        assertEquals(listOf("154"), meterReadings("18:02\n154 м³").map { it.value })
        assertEquals("154", meterFacts("18:02\n154 м³")[META_ENTITY_METER])
        assertTrue(meterReadings("18:02\n154 м³").none { '\n' in it.value })

        assertEquals(listOf("20 842"), meterReadings("Показання 20 842 кВт·ч").map { it.value })
    }
}
