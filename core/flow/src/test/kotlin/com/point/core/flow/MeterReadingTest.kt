package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Показание счётчика — факт, которого у объекта не было вовсе (#262). Три кадра корпуса из
 * двадцати двух ждали именно его, и мерить их было нечем.
 *
 * Полюса здесь ровно два: показание против случайного числа. Правило видит форму («число рядом
 * с единицей учёта») и ничего больше — оно размечает, а не решает, поэтому улика у него одна.
 */
class MeterReadingTest {

    @Test
    fun `показание света становится фактом с происхождением «прочитано» и одной уликой формы`() {
        // Кадр 17 корпуса: фото электросчётчика.
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
        // Сумма, трек и голый номер счёта стоят на тех же страницах и той же формы.
        assertTrue(meterFacts("Сума до сплати 20842 грн").isEmpty())
        assertTrue(meterFacts("ТТН 20 4514 9154 9395 прибула").isEmpty())
        assertTrue(meterFacts("Особовий рахунок 305412").isEmpty())
    }

    @Test
    fun `объём в прозе — не показание табло`() {
        // «2 м³ бетону» и «спожито 12 м³» — числа с той же единицей, но показаний в них нет.
        assertTrue(meterReadings("залито 2 м³ бетону").isEmpty())
        assertTrue(meterReadings("спожито 12 м3 за місяць").isEmpty())
    }

    @Test
    fun `длинный номер рядом с единицей — номер счётчика, а не обрезок показания`() {
        // Тот же урок, что «рахунок 202045149154939512» не трек: границы числа стерегутся.
        assertTrue(meterReadings("лічильник №20842123456 кВт·ч").isEmpty())
    }

    @Test
    fun `разрядный пробел — часть показания, а не его граница`() {
        // Ревью #262: «20 842 кВт·ч» читалось последней тройкой — правило писало `842` и
        // помечало его src=ocr, то есть выдавало выдуманное число за прочитанное дословно.
        // Молчаливая порча значения хуже молчания.
        assertEquals("20 842", meterFacts("Спожито 20 842 кВт·ч")[META_ENTITY_METER])
        assertEquals("20 842,5", meterFacts("Показання 20 842,5 кВт·ч")[META_ENTITY_METER])
    }

    @Test
    fun `разрядный пробел не ссорит правило с моделью — пробел складывается`() {
        // Значение с пробелом безопасно ровно потому, что normConsensus его складывает:
        // «20 842» правила и «20842» модели — согласие, а не спор о числе.
        assertEquals(normConsensus("20842"), normConsensus(meterFacts("Спожито 20 842 кВт·ч")[META_ENTITY_METER]!!))
        assertEquals(true, semanticFits(META_ENTITY_METER, "20 842"))
    }

    @Test
    fun `разрядные группы — ровно по три цифры, иначе это соседние числа`() {
        // Склеивается только настоящая разрядная запись; «1234 5678» — два числа, и правило
        // не имеет права выдать из них одно восьмизначное.
        assertEquals("1 234 567", meterReadings("Спожито 1 234 567 м³").single().value)
        assertEquals("5678", meterReadings("1234 5678 м³").single().value)
    }

    @Test
    fun `разрядное число длиннее табло не режется до последней тройки`() {
        // «123 456 789» — девять цифр, это номер, а не показание. Раньше правило отдавало от
        // него хвост `789`: обрезок, помеченный как прочитанный дословно.
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
        // Регистр складывается с юникодом: без этого «20842 КВТ·Ч» с фотографии табло
        // проходило бы мимо правила молча.
        assertEquals("20842", meterFacts("ПОКАЗАННЯ 20842 КВТ·Ч")[META_ENTITY_METER])
    }

    @Test
    fun `нет показания — нет ключей, а не ключ с пустотой`() {
        assertTrue(meterFacts("Позвони на +380671234567").isEmpty())
        assertTrue(meterFacts("").isEmpty())
    }

    @Test
    fun `форма показания судится одной реализацией — правилом и валидатором кандидатов`() {
        // Два счётчика формы разъехались бы на первой правке (#262): валидатор улик обязан
        // судить теми же границами, что офлайновое правило.
        assertEquals(true, semanticFits(META_ENTITY_METER, "20842"))
        assertEquals(false, semanticFits(META_ENTITY_METER, "12"))
    }

    @Test
    fun `ведущие нули барабана остаются в значении дословно`() {
        // Живой кадр устройства — водомер отдал «00001154», и это 154 м³. Обрезать нули молча
        // нельзя, сколько разрядов значащие — знает поставщик услуги, а не Point.
        val facts = meterFacts("Показання 00001154 м³")

        assertEquals("00001154", facts[META_ENTITY_METER])
        assertEquals("м³", facts[META_ENTITY_METER_UNIT])
    }

    @Test
    fun `подсказка не становится фактом — в метаданных живёт только дословное`() {
        // Производная, которой никто не читал, в метаданных факта была бы значением без
        // происхождения — и разъехалась бы со значением при первом же голосовании.
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
        // Прецедент молчания — provenanceLabel(GIVEN) и readingModeLabel(PRINTED): норма не
        // подписывается, иначе подпись превращается в шум.
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
        // Пустая строка на месте подсказки была бы худшим исходом: «показание есть, а числа
        // нет». Поэтому последний ноль остаётся.
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
        // Писателей у факта трое, и модель («Понять», ключ METER) формой не судится —
        // semanticFits размечает, а не решает. Значит в entity.meter законно ложится
        // «0 показань», и срезать у него «нули» значило бы посоветовать передать «показань».
        assertNull(meterWithoutDrumZeros("0 показань"))
        assertNull(meterWithoutDrumZeros("0 куб.м знято"))
        // Единица живёт своим ключом — склеенное значение Point числом табло не признаёт.
        assertNull(fieldHint(META_ENTITY_METER, "00154 м³"))
    }

    @Test
    fun `пробелы по краям подсказку не выдумывают`() {
        // Подсказка обязана отличаться от значения по сути, а не по пробелам, иначе карточка
        // предложит «передать» ровно то, что уже показала.
        assertNull(meterWithoutDrumZeros(" 0 "))
        assertEquals("1154", meterWithoutDrumZeros(" 00001154 "))
    }

    @Test
    fun `подсказка есть только у показания — чужой ведущий ноль часть номера`() {
        // У трека и телефона ноль впереди — не оформление барабана, а сам номер, и срезать
        // его значило бы отслеживать чужую посылку.
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
        // Тот же дефект, что найден у суммы (ревью #262): `\s` в Java включает `\n`, и разрядной
        // группой оказывался кусок соседней строки. Показание кадра 03 приходит из переписки, где
        // над снимком счётчика стоит таймстемп, — «02⏎154» человек передал бы поставщику услуги
        // как прочитанное с табло дословно.
        assertEquals(listOf("154"), meterReadings("18:02\n154 м³").map { it.value })
        assertEquals("154", meterFacts("18:02\n154 м³")[META_ENTITY_METER])
        assertTrue(meterReadings("18:02\n154 м³").none { '\n' in it.value })
        // Разрядный пробел внутри строки при этом остаётся частью числа.
        assertEquals(listOf("20 842"), meterReadings("Показання 20 842 кВт·ч").map { it.value })
    }
}
