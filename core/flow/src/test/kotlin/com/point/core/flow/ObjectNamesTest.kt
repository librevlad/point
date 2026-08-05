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
}
