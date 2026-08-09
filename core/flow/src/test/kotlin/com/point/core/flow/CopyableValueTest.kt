package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Мне в буфере целиковые блоки не нужны» (владелец, 2026-08-09): копия строки
 * действия — одно ключевое значение, то же, что строка показывает. Прежний тест
 * закреплял склейку «label: value» всех полей — отменён этим правилом.
 */
class CopyableValueTest {

    private fun spec(key: String, label: String, critical: Boolean = false) =
        FieldSpec(key, label, critical = critical)

    private fun reading(key: String, label: String, value: String, critical: Boolean = false) =
        FieldReading(spec(key, label, critical), value)

    @Test
    fun `один найденный факт копируется как есть`() {
        val readiness = Readiness.Ready(listOf(reading("track", "номер", "NOT0000123456")))

        assertEquals("NOT0000123456", copyableValue(readiness))
    }

    @Test
    fun `несколько фактов — в буфер идёт ключевое значение, а не целиковый блок`() {
        val readiness = Readiness.Ready(
            listOf(
                reading("track", "трек-номер", "UA79322001000026208373515609", critical = true),
                reading("date", "дата", "26.04.2026"),
            ),
        )

        assertEquals("UA79322001000026208373515609", copyableValue(readiness))
    }

    @Test
    fun `ключевое значение побеждает и когда стоит не первым`() {
        val readiness = Readiness.Ready(
            listOf(
                reading("date", "дата", "26.04.2026"),
                reading("card", "карта", "4149 6090 5716 5427", critical = true),
            ),
        )

        assertEquals("4149 6090 5716 5427", copyableValue(readiness))
    }

    @Test
    fun `неготовое действие отдаёт то, что уже нашлось`() {
        val readiness = Readiness.Missing(
            missing = listOf(spec("amount", "сумма")),
            present = listOf(reading("iban", "счёт", "UA12 3456 7890")),
        )

        assertEquals("UA12 3456 7890", copyableValue(readiness))
    }

    @Test
    fun `несколько побочных полей без ключевого — копия молчит, а не клеит блок`() {
        val readiness = Readiness.Ready(
            listOf(
                reading("carrier", "перевозчик", "Нова Пошта"),
                reading("date", "дата", "26.04.2026"),
            ),
        )

        assertNull(copyableValue(readiness))
    }

    @Test
    fun `копировать нечего — строка молчит`() {
        assertNull(copyableValue(Readiness.Ready(emptyList())))
        assertNull(copyableValue(Readiness.Missing(listOf(spec("a", "а")), emptyList())))
        assertNull(copyableValue(Readiness.Ready(listOf(reading("track", "номер", "   ")))))
    }
}
