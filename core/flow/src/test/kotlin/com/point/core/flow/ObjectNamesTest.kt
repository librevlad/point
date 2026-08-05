package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Имя объекта, которое Point даёт сам (#533).
 *
 * Находка живого прогона 04.08.2026: в «Недавнем» две строки `receipt.png` подряд различались
 * ТОЛЬКО относительным временем под ними, а расшаренный текст назывался
 * `shared-5631909340713910696.txt`. Имя судится здесь, а не глазами на телефоне: это чистые
 * функции, и цена ошибки в них — нечитаемое «Недавнее» у каждого объекта сразу.
 */
class ObjectNamesTest {

    /** Пояс задаётся числом, а не именем города: имена в базе часовых поясов переименовывают
     *  (Kiev → Kyiv), и тест начал бы падать от обновления JDK, а не от поломки Point. */
    private val kyiv = ZoneOffset.ofHours(3)

    /** 4 августа 2026, 19:25 — тот самый пример из #533. */
    private val aug4at1925 = java.time.ZonedDateTime.of(2026, 8, 4, 19, 25, 13, 0, kyiv)
        .toInstant().toEpochMilli()

    @Test
    fun `имя тексту дают его собственные первые слова`() {
        assertEquals("Пришлите договор до пятницы", textObjectName("Пришлите договор до пятницы"))
    }

    @Test
    fun `длинный текст режется по границе слова и говорит об этом многоточием`() {
        val name = textObjectName(
            "Прошу подтвердить получение документов по договору поставки от 4 августа",
        )
        assertTrue("имя не обрезано - строка «Недавнего» его не покажет: $name", name.length <= 41)
        assertTrue("обрублено посреди слова: $name", name.endsWith("…"))
        assertTrue("режем по словам, а не по буквам: $name", name.trimEnd('…').split(" ").last().isNotEmpty())
        assertEquals("Прошу подтвердить получение документов…", name)
    }

    @Test
    fun `перевод строки не рвёт имя на две`() {
        assertEquals("Олена Ковальчук +380 67 123 45 67", textObjectName("Олена Ковальчук\n+380 67 123 45 67"))
    }

    /** Имя доезжает до «Загрузок» при сохранении: `/` и `:` там означают путь, а не буквы. */
    @Test
    fun `знаки, ломающие имя файла, в имя не попадают`() {
        val name = textObjectName("отчёт/квартал: 2026\\Q3")
        assertTrue("в имени остался разделитель пути: $name", name.none { it in "/\\:*?\"<>|" })
    }

    @Test
    fun `хвостовая пунктуация в имя не входит`() {
        assertEquals("Здравствуйте", textObjectName("Здравствуйте,"))
        assertEquals("Готово", textObjectName("Готово!   "))
    }

    @Test
    fun `пустому тексту имя всё равно нужно`() {
        assertEquals("Текст", textObjectName("   \n\t "))
        assertEquals("Текст", textObjectName(""))
    }

    @Test
    fun `запись и снимок называются собой и временем`() {
        assertEquals("Запись, 4 авг 19:25", stampedObjectName("Запись", aug4at1925, kyiv))
        assertEquals("Снимок, 4 авг 19:25", stampedObjectName("Снимок", aug4at1925, kyiv))
    }

    /** Имя даётся один раз и живёт с объектом: «3 часа назад» в нём соврало бы завтра. */
    @Test
    fun `имя не меняется от того, когда его читают`() {
        val first = stampedObjectName("Запись", aug4at1925, kyiv)
        val second = stampedObjectName("Запись", aug4at1925, kyiv)
        assertEquals(first, second)
    }

    // --- Машинное имя опознаётся, а не пропускается дальше (#581) ---

    @Test
    fun `имя без единого слова — машинное`() {
        // Ровно то, что живой прогон показал в «Недавнем»: четыре строки подряд, различимые
        // только цифрами. Объект среди них человек не находит.
        listOf(
            "shared-1998027363737203759.txt",
            "shared-5388726963827559860.txt",
            "record-1754325912345.m4a",
            "IMG_20260805_120000.jpg",
            "shot-1754325912345.jpg",
            "20260805_120000.pdf",
            "",
            null,
        ).forEach { assertTrue("прошло как человеческое: " + it, looksMachineName(it)) }
    }

    @Test
    fun `имя со словом — человеческое, даже короткое`() {
        // Своё имя человек даёт как хочет, и спорить с ним не наше дело: `receipt.png` — плохое
        // имя, но оно ЕГО. Правило ловит только те имена, которые Point выдумал сам.
        listOf(
            "Договор аренды.pdf",
            "receipt.png",
            "накладная 4512.xlsx",
            "Отчёт за август.docx",
            "photo от Ирины.jpg",
        ).forEach { assertTrue("принято за машинное: " + it, !looksMachineName(it)) }
    }
}
