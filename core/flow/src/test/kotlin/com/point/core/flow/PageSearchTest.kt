package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск по странице (#279): тот же адрес значения, что у выделения (#259), только запрос печатает
 * человек. Проверяется главное обещание — правила сравнения ровно те же, что у свода чтений
 * ([normConsensus]), и ни на йоту мягче.
 */
class PageSearchTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    /** Строка накладной, как её отдаёт движок: слова разорваны, оформление своё у каждого. */
    private val page = AtomLayer(
        listOf(
            atom("a1", "Отправитель:", 10f, 100f, 150f, 120f),
            atom("a2", "Иванов", 160f, 100f, 230f, 120f),
            atom("a3", "И.", 235f, 100f, 260f, 120f),
            atom("b1", "Спожито", 10f, 200f, 90f, 220f),
            atom("b2", "20 842", 95f, 200f, 170f, 220f),
            atom("b3", "кВт·ч", 175f, 200f, 240f, 220f),
            atom("c1", "Одержувач", 10f, 300f, 120f, 320f),
            atom("c2", "Іванов", 125f, 300f, 195f, 320f),
        ),
    )

    @Test
    fun `слово находится в другом регистре и с другим оформлением`() {
        val hits = page.findOnPage("отправитель")

        assertEquals(1, hits.size)
        assertEquals("Отправитель:", hits.single().text)
    }

    /** Движок разорвал «Иванов И.» на два атома — запрос человека обязан пережить этот разрыв. */
    @Test
    fun `запрос из двух слов находится через границу атомов`() {
        val hits = page.findOnPage("Иванов И.")

        assertEquals(1, hits.size)
        assertEquals(listOf("a2", "a3"), hits.single().ids)
        assertEquals(Box(160f, 100f, 260f, 120f), hits.single().region)
    }

    @Test
    fun `чего на странице нет — того нет, и это говорится прямо`() {
        assertEquals(emptyList<PageMatch>(), page.findOnPage("Петров"))
        assertEquals("Ничего не нашлось", foundOnPageLabel(page.findOnPage("Петров").size))
    }

    /** Разрядный пробел — оформление числа, а не его граница: то же правило, что у голосования. */
    @Test
    fun `показание находится и с разрядным пробелом, и без него`() {
        assertEquals("20 842", page.findOnPage("20842").single().text)
        assertEquals("20 842", page.findOnPage("20 842").single().text)
    }

    /**
     * Пропавшая запятая — другое число, а не другое оформление (#294). Мягкий поиск нашёл бы
     * здесь «десятку» и научил человека доверять находке, которой на странице нет.
     */
    @Test
    fun `число с запятой не находится по числу без неё`() {
        val prices = AtomLayer(listOf(atom("p", "1,0", 0f, 0f, 30f, 20f)))

        assertTrue(prices.findOnPage("10").isEmpty())
        assertEquals("1,0", prices.findOnPage("1.0").single().text)
    }

    /** Никакого «похоже на то, что вы искали»: ремонт букв судит спор двух чтений одного места,
     *  а здесь второго чтения нет — есть страница и слово человека. */
    @Test
    fun `похожее слово находкой не считается`() {
        assertTrue(page.findOnPage("Иваненко").isEmpty())
        assertTrue(page.findOnPage("Отправка").isEmpty())
    }

    /** Строки склеены не будут: находка через перенос — утверждение про страницу, которого на
     *  странице нет. «И.» — последнее слово первой строки, «Спожито» — первое второй. */
    @Test
    fun `находка не перепрыгивает границу строки`() {
        assertTrue(page.findOnPage("И. Спожито").isEmpty())
    }

    @Test
    fun `запрос, в котором нечего искать, — ещё не искали, а не «не нашлось»`() {
        // «Ещё не искали» — это не только пустое поле: строка из одного оформления складывается
        // в пустоту теми же правилами. Правило одно — и на экран, и на реализатор.
        assertFalse(isSearchable(""))
        assertFalse(isSearchable("   "))
        assertFalse(isSearchable("—  ..."))
        assertTrue(isSearchable("Іванов"))

        assertTrue(page.findOnPage("").isEmpty())
        assertTrue(page.findOnPage("—").isEmpty())
    }

    /** Место — то, на что можно показать пальцем: две подстроки внутри одного слова подсветятся
     *  одной рамкой, и счёт обязан говорить про рамки, а не про комбинаторику. */
    @Test
    fun `два вхождения внутри одного слова — одно место`() {
        val song = AtomLayer(listOf(atom("s", "мамамама", 0f, 0f, 80f, 20f)))

        assertEquals(1, song.findOnPage("мама").size)
    }

    @Test
    fun `находки идут по странице сверху вниз, каждая со своей рамкой`() {
        val hits = page.findOnPage("Іванов")

        assertEquals(1, hits.size)
        assertEquals(Box(125f, 300f, 195f, 320f), hits.single().region)
    }

    /** Координаты страниц PDF лежат в одном пространстве — поиск по первой не смеет найти на
     *  второй (то же правило, что у рамки выделения). */
    @Test
    fun `поиск идёт по своей странице`() {
        val twoPages = AtomLayer(
            listOf(
                atom("p0", "Договір", 10f, 100f, 100f, 120f),
                Atom("p1", "Договір", Box(10f, 100f, 100f, 120f), page = 1),
            ),
        )

        assertEquals(listOf("p0"), twoPages.findOnPage("договір").single().ids)
        assertEquals(listOf("p1"), twoPages.findOnPage("договір", page = 1).single().ids)
    }

    /** Адрес находки — тот же набор атомов, что у выделения, и он проходит общий валидируемый
     *  резолвер: поиск не заводит вторую подсистему адресации. */
    @Test
    fun `находка адресуется набором атомов через общий резолвер без потерь`() {
        val hit = page.findOnPage("Иванов И.").single()
        val value = page.resolve(AtomAddress.ByIds(hit.ids))

        assertEquals("Иванов И.", value.text)
        assertTrue(value.droppedIds.isEmpty())
    }

    @Test
    fun `число находок называется по-русски`() {
        assertEquals("Ничего не нашлось", foundOnPageLabel(0))
        assertEquals("Нашлось 1 место", foundOnPageLabel(1))
        assertEquals("Нашлось 3 места", foundOnPageLabel(3))
        assertEquals("Нашлось 5 мест", foundOnPageLabel(5))
        assertEquals("Нашлось 11 мест", foundOnPageLabel(11))
        assertEquals("Нашлось 21 место", foundOnPageLabel(21))
        assertEquals("Нашлось 22 места", foundOnPageLabel(22))
    }
}
