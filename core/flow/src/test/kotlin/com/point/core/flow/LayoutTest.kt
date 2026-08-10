package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение владельца (#682/#683) «читать больше и умнее»: резать текст по границам
 * предложений, а не вслепую по числу символов. У абзаца без переносов строк раньше
 * в модель уходила только первая треть тысячи символов одной «строки» — остальное
 * молча терялось и объявлялось пониманием объекта.
 */
class LayoutTest {

    private fun words(text: String): List<String> =
        text.split(Regex("""\s+""")).filter { it.isNotBlank() }

    private val longLetter = listOf(
        "Добрый день!",
        "Пишу по поводу заказа номер 48213, который мы оформили на прошлой неделе и " +
            "до сих пор не получили никакого подтверждения отправки.",
        "Курьер должен был приехать во вторник, но не приехал, а на звонки в поддержку " +
            "никто не отвечает уже третий день подряд.",
        "Прошу разобраться в ситуации и сообщить, когда именно ожидать посылку, потому " +
            "что дальше ждать без объяснений уже не получается.",
        "Если решить вопрос не выйдет в ближайшее время, буду вынужден обратиться за " +
            "возвратом полной суммы заказа через банк, о чём заранее предупреждаю.",
    ).joinToString(" ")

    @Test
    fun `короткая строка остаётся одним элементом`() {
        val elements = layoutOf("Нова Пошта, відділення №9")

        assertEquals(1, elements.size)
        assertEquals("Нова Пошта, відділення №9", elements.single().text)
    }

    @Test
    fun `текст с переносами строк по-прежнему режется по строкам`() {
        val text = "Іваненко Іван Петрович\nвул. Хрещатик, 1\n+380 67 123 45 67"

        val elements = layoutOf(text)

        assertEquals(
            listOf("Іваненко Іван Петрович", "вул. Хрещатик, 1", "+380 67 123 45 67"),
            elements.map { it.text },
        )
    }

    @Test
    fun `пустые строки не рождают элемент, нумерация остаётся сплошной`() {
        assertEquals(listOf("P1", "P2"), layoutOf("a\n\n   \nb").map { it.id })
    }

    @Test
    fun `длинный абзац без единого переноса раньше терялся после 300 знаков — теперь виден весь`() {
        assertTrue("тестовое письмо обязано быть длиннее одного элемента", longLetter.length > 300)

        val elements = layoutOf(longLetter)

        assertTrue("длинный абзац обязан стать несколькими элементами", elements.size > 1)

        // Ничего не потеряно: все слова исходного письма возвращаются в том же порядке.
        assertEquals(words(longLetter), elements.flatMap { words(it.text) })
    }

    @Test
    fun `каждый кусок длинного абзаца встречается в исходнике дословно — резалось по границе, не по букве`() {
        val elements = layoutOf(longLetter)

        elements.forEach { el ->
            assertTrue("«${el.text}» не найден дословно в письме", longLetter.contains(el.text))
        }
    }

    @Test
    fun `предложение длиннее предела режется по границе слова, а не буквы`() {

        // Без единой точки — одно «предложение» на 700+ знаков.
        val longRun = (1..80).joinToString(" ") { "слово$it" }

        val elements = layoutOf(longRun)

        assertTrue(elements.size > 1)
        assertEquals(words(longRun), elements.flatMap { words(it.text) })
    }

    @Test
    fun `короткий текст не режется вовсе`() {
        val elements = layoutOf("Всё в одну строку и коротко.")

        assertEquals(1, elements.size)
        assertEquals("Всё в одну строку и коротко.", elements.single().text)
    }

    @Test
    fun `общий предел числа элементов не снят`() {
        val many = (1..500).joinToString("\n") { "строка $it" }

        assertEquals(MAX_LAYOUT_ELEMENTS, layoutOf(many).size)
    }

    @Test
    fun `у каждого элемента порядковый id без пропусков`() {
        val paragraph = "Раз. Два три. Четыре пять шесть. ".repeat(40)

        val elements = layoutOf(paragraph)

        assertEquals((1..elements.size).map { "P$it" }, elements.map { it.id })
    }

    // readWindowOf — окно чтения длинного объекта (#682/#683): следующее нажатие «Понять»
    // продолжает с того места, где остановилось прошлое, и не рвёт слово на границе окна.

    @Test
    fun `окно короче предела возвращает весь остаток`() {
        assertEquals("хвост", readWindowOf("хвост", already = 0, limit = 100))
    }

    @Test
    fun `окно начинается с указанного места`() {
        val full = "0123456789"

        assertEquals("34567", readWindowOf(full, already = 3, limit = 5))
    }

    @Test
    fun `окно, упёршееся в предел ровно на границе слова, не меняется`() {
        val full = "раз два три четыре"

        assertEquals("раз два", readWindowOf(full, already = 0, limit = 7))
    }

    @Test
    fun `окно посреди слова откатывается назад к пробелу, а не рвёт слово`() {
        val full = "раз два три четыре"

        // limit=9 обрывает «три» на «тр» — окно обязано откатиться к концу «два».
        val window = readWindowOf(full, already = 0, limit = 9)

        assertEquals("раз два", window)
        assertTrue(full.startsWith(window))
        assertFalse("слово «три» не должно быть разорвано", window.endsWith("тр"))
    }

    @Test
    fun `хвост, обрезанный первым окном, целиком приходит со вторым — ничего не теряется`() {
        val full = "раз два три четыре пять"

        val first = readWindowOf(full, already = 0, limit = 9)
        val second = readWindowOf(full, already = first.length, limit = 100)

        assertEquals(full, first + second)
    }

    @Test
    fun `окно, достающее до конца объекта, не обрезается — резать больше нечего`() {
        val full = "раз два три"

        assertEquals(full, readWindowOf(full, already = 0, limit = 100))
    }

    @Test
    fun `неразрывный поток без пробелов режется по пределу — предел размера не снимается`() {
        val full = "а".repeat(50)

        // Пробела для отката назад нет: предел важнее, чем не резать посреди «слова» —
        // иначе окно росло бы неограниченно на любом тексте без пробелов.
        val window = readWindowOf(full, already = 0, limit = 10)

        assertEquals("а".repeat(10), window)
    }

    @Test
    fun `неразрывный поток без пробелов дочитывается следующими окнами целиком`() {
        val full = "а".repeat(25)

        val first = readWindowOf(full, already = 0, limit = 10)
        val second = readWindowOf(full, already = first.length, limit = 10)
        val third = readWindowOf(full, already = first.length + second.length, limit = 10)

        assertEquals(full, first + second + third)
    }
}
