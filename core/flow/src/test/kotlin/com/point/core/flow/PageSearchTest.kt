package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск по странице (#279): тот же адрес значения, что у выделения (#259), только запрос печатает
 * человек. Проверяется главное обещание — **что напечатано на странице, находится по дословному
 * запросу**, и правила сравнения при этом не мягче свода чтений: пропавшая запятая остаётся
 * другим числом (#294).
 *
 * Обе половины обещания меряются на слоях настоящего прогона (см. «строка настоящего кадра»
 * ниже): сочинённая страница складывается ровно так, как её сложил автор правила, и потому
 * молчит о том, что живая строка приходит атомами вроде «14.2,» и «(01.07.24».
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
        assertEquals(Box(125f, 300f, 195f, 320f), page.findOnPage("Іванов").single().region)

        // Две находки на разных строках приходят в порядке страницы, а не выдачи движка:
        // счёт «нашлось 2» без порядка — список, по которому нельзя идти глазами.
        val both = page.findOnPage("ов")
        assertEquals(listOf(listOf("a2"), listOf("c2")), both.map { it.ids })
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

    // --- строка настоящего кадра: слова с числами (ревью #279) ---

    /**
     * Дословный след кадра 04 корпуса (прогон 03.08.2026, A34): строка техкарты, где ссылка на
     * приказ идёт рядом с датой и номерами пунктов. Лист снят боком (rotation=90), поэтому строка
     * собирается по выпрямленному кадру — тот же путь, что у [AtomLayer.lines].
     *
     * Числа здесь — цитата публичного документа, не данные владельца.
     */
    private val realLine = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/table_04_dates.atoms.tsv")) {
            "нет фикстуры кадра 04"
        }.bufferedReader().readText(),
    )

    /**
     * Главная поломка, найденная ревью: слово рядом с числом переставало находиться.
     *
     * Свод чтений складывает **токен целиком** — «01.12.2020» он канонизирует как число
     * («0112.2020»), а «ад! 01.12.2020» числом уже не считает и стирает точки («ад!01122020»).
     * Страница складывалась по атому, запрос — целиком, и два правила не встречались никогда.
     * На слоях корпуса так молчали 70 из 1571 соседних пар: «ВЙСЬКТОРГ 78.00» из таблицы
     * договоров, «24.18 мин.» из маршрута, «30.03 Михайло-Лукашеве» из посылки.
     */
    @Test
    fun `дата рядом со словом находится по дословному запросу`() {
        assertEquals(listOf("w262", "w263"), realLine.findOnPage("ад! 01.12.2020").single().ids)
        assertEquals(listOf("w263", "w264", "w265"), realLine.findOnPage("01.12.2020 № 2489").single().ids)
    }

    /** Обратная сторона той же поломки: у слипшегося атома «14.2,» точка стиралась вместе с
     *  запятой, и пункт находился по «142» — числу, которого на странице нет (#294). */
    @Test
    fun `номер пункта находится собой, а не числом без точки`() {
        assertEquals(listOf("w268"), realLine.findOnPage("14.2").single().ids)
        assertTrue(realLine.findOnPage("142").isEmpty())
    }

    /** Та же проверка на форме расчёта (кадр 03 — сумма к оплате приходит одним атомом вместе со
     *  знаками формулы): сумма обязана находиться собой и не находиться без разделителя. */
    @Test
    fun `сумма внутри слипшегося куска находится собой, а не числом без запятой`() {
        val calc = AtomLayer(listOf(atom("c", "3*1.5=4,50", 0f, 0f, 80f, 20f)))

        assertEquals("3*1.5=4,50", calc.findOnPage("4,50").single().text)
        assertTrue(calc.findOnPage("450").isEmpty())
    }

    /** Человек печатает пробел там, где движок разорвал строку без него (кадр 01: «2.» и «1!» —
     *  два атома). Разделитель судится по соседям с точностью до оформления, поэтому запрос и
     *  страница складываются одинаково. */
    @Test
    fun `пробел в запросе на месте разрыва атомов находке не мешает`() {
        val split = AtomLayer(
            listOf(atom("a", "2.", 0f, 0f, 20f, 20f), atom("b", "1!", 25f, 0f, 45f, 20f)),
        )

        assertEquals(listOf("a", "b"), split.findOnPage("2. 1!").single().ids)
    }
}
