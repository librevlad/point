package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopyableValueTest {

    private fun spec(key: String, label: String) = FieldSpec(key, label)

    private fun reading(key: String, label: String, value: String) =
        FieldReading(spec(key, label), value)

    @Test
    fun `один найденный факт копируется как есть`() {
        val readiness = Readiness.Ready(listOf(reading("track", "номер", "NOT0000123456")))

        assertEquals("NOT0000123456", copyableValue(readiness))
    }

    @Test
    fun `несколько фактов копируются с подписями, чтобы не слиплись`() {
        val readiness = Readiness.Ready(
            listOf(
                reading("iban", "счёт", "UA12 3456 7890"),
                reading("amount", "сумма", "1 200,50"),
            ),
        )

        assertEquals("счёт: UA12 3456 7890\nсумма: 1 200,50", copyableValue(readiness))
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
    fun `копировать нечего — строка молчит`() {
        assertNull(copyableValue(Readiness.Ready(emptyList())))
        assertNull(copyableValue(Readiness.Missing(listOf(spec("a", "а")), emptyList())))
        assertNull(copyableValue(Readiness.Ready(listOf(reading("track", "номер", "   ")))))
    }
}
