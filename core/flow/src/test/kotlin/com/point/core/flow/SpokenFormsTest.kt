package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Речь пишется словами, а не знаками (#1426, живая охота 03.09.2026).
 *
 * Whisper отдал дословно: «Meeting with Enna on Friday, September 11 at 3 p.m. in Kyivie,
 * Kreschetyk Street 22. Bring the invoice number 4471 and call plus 380671234567.» Правила
 * сущностей выросли на тексте с экрана, где стоят знаки, — и не нашли ни телефона, ни даты.
 */
class SpokenFormsTest {

    private val extractor = RegexEntityExtractor()

    private suspend fun of(text: String, type: EntityType): List<String> =
        extractor.extract(text).filter { it.type == type }.map { it.value }

    @Test fun `устный «plus» перед цифрами — знак номера`() = runTest {
        val said = "Bring the invoice number 4471 and call plus 380671234567."

        assertEquals(listOf("+380671234567"), of(said, EntityType.PHONE))
    }

    @Test fun `«плюс» по-русски — тот же знак, номер с пробелами речи`() = runTest {
        val said = "Перезвони мне, плюс 380 67 123 45 67, после обеда"

        assertEquals(listOf("+380 67 123 45 67"), of(said, EntityType.PHONE))
    }

    @Test fun `точка в конце предложения номер не отменяет`() = runTest {
        // До #1426 «+380 67 123 45 67.» с экрана тоже не находился: правило запрещало точку после номера.
        assertEquals(listOf("+380 67 123 45 67"), of("Позвони мне: +380 67 123 45 67.", EntityType.PHONE))
        assertEquals(emptyList<String>(), of("Версия 192.168.001.001.5 не читается номером", EntityType.PHONE))
    }

    @Test fun `слово plus без цифр за ним номером не становится`() = runTest {
        assertEquals(emptyList<String>(), of("Point plus ICG: thesis about free resources", EntityType.PHONE))
    }

    @Test fun `английская дата с годом читается днём — месяцем вперёд и днём вперёд`() = runTest {
        assertEquals(listOf("September 11, 2026"), of("Meeting on September 11, 2026 at 3 p.m.", EntityType.DATE_TIME))
        assertEquals(listOf("11 September 2026"), of("Deadline 11 September 2026", EntityType.DATE_TIME))

        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("September 11, 2026"))
        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("11 September 2026"))
        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("September 11th, 2026"))
    }

    @Test fun `похожее на месяц слово месяцем не становится`() = runTest {
        // Трёхбуквенная основа с любым хвостом делала бы датами имена и обычные слова.
        val words = "Market 12, 2026 report. Janet 3, 2026. Order of 5 Junior 2026"

        assertEquals(emptyList<String>(), of(words, EntityType.DATE_TIME))
        assertNull(humanDayOf("Market 12, 2026"))
        assertNull(humanDayOf("5 Junior 2026"))
    }

    @Test fun `принятое сокращение месяца читается днём`() = runTest {
        assertEquals(listOf("Sept. 11, 2026"), of("Due Sept. 11, 2026", EntityType.DATE_TIME))
        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("11 Sep 2026"))
        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("Sept. 11, 2026"))
    }

    @Test fun `без года дня нет — и по-английски тоже`() = runTest {
        // Правило прежнее («5 серпня» без года — не день): выдумать год хуже, чем не прочитать.
        assertNull(humanDayOf("September 11"))
        assertEquals(emptyList<String>(), of("Friday, September 11 at 3 p.m.", EntityType.DATE_TIME))
    }
}
