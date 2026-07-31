package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Притягивание выделения к атомам (#259) — тот же дословный трек `20 4514 9154 9395` тремя
 * кусками, что в [ValueAddressTest]: кривая рамка человека обязана захватить номер целиком,
 * а «честно не нашли» поле, лежащее под пальцем, — новый способ соврать.
 */
class SelectionTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    private val layer = AtomLayer(
        listOf(
            atom("a3", "9395", 145f, 100f, 190f, 120f),
            atom("a1", "20", 10f, 100f, 40f, 120f),
            atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
            atom("far", "Отправитель", 10f, 900f, 150f, 930f),
        ),
    )

    @Test
    fun `кривая рамка, зацепившая куски номера, захватывает их целиком в порядке чтения`() {
        // Рамка косая: начало внутри «20», конец внутри «9395», по высоте зацеплена половина строки.
        val s = layer.snapSelection(Box(30f, 110f, 150f, 125f))

        assertEquals("20 4514 9154 9395", s.text)
        assertEquals(Box(10f, 100f, 190f, 120f), s.region) // расширена до целых слов
    }

    @Test
    fun `рамка меньше слова захватывает слово целиком`() {
        val s = layer.snapSelection(Box(60f, 105f, 70f, 115f))

        assertEquals("4514 9154", s.text)
        assertEquals(Box(45f, 100f, 140f, 120f), s.region)
    }

    @Test
    fun `слово в другом углу страницы не захватывается`() {
        val s = layer.snapSelection(Box(0f, 90f, 200f, 130f))

        assertFalse("far" in s.ids)
        assertEquals("20 4514 9154 9395", s.text)
    }

    @Test
    fun `рамка по пустому месту — пустой захват, рамка не тронута, не ошибка`() {
        val raw = Box(300f, 300f, 400f, 400f)
        val s = layer.snapSelection(raw)

        assertTrue(s.atoms.isEmpty())
        assertEquals("", s.text)
        assertEquals(raw, s.region)
    }

    /** Долговечный адрес захвата — атомы, и он проходит тот же валидируемый резолвер, что и
     *  ответ модели: выделение — не вторая подсистема адресации (#259). */
    @Test
    fun `захват адресуется набором атомов через общий резолвер без потерь`() {
        val s = layer.snapSelection(Box(30f, 110f, 150f, 125f))
        val v = layer.resolve(AtomAddress.ByIds(s.ids))

        assertEquals(s.text, v.text)
        assertTrue(v.droppedIds.isEmpty())
        assertFalse(v.disjoint)
    }

    @Test
    fun `захват двух строк читается сверху вниз, а не в порядке выдачи ридера`() {
        val s = layer.snapSelection(Box(0f, 0f, 500f, 1000f))

        assertEquals("20 4514 9154 9395 Отправитель", s.text)
    }

    @Test
    fun `касание краем считается захватом — щедрость к неточному пальцу`() {
        val s = layer.snapSelection(Box(0f, 120f, 12f, 140f)) // верхний край рамки касается низа «20»

        assertEquals("20", s.text)
    }

    /** Координаты страниц PDF лежат в одном пространстве — рамка на первой странице не смеет
     *  молча захватить слова второй. */
    @Test
    fun `рамка захватывает только свою страницу`() {
        val twoPages = AtomLayer(
            listOf(
                atom("p0", "первая", 10f, 100f, 100f, 120f),
                Atom("p1", "вторая", Box(10f, 100f, 100f, 120f), page = 1),
            ),
        )

        assertEquals("первая", twoPages.snapSelection(Box(0f, 90f, 120f, 130f)).text)
        assertEquals("вторая", twoPages.snapSelection(Box(0f, 90f, 120f, 130f), page = 1).text)
    }

    // -- находки ревью #284 --

    /** Палец ведут из любого угла: перевёрнутая рамка жеста — тот же захват, а не молча пустой. */
    @Test
    fun `перевёрнутая рамка жеста захватывает то же, что выпрямленная`() {
        val straight = layer.snapSelection(Box(30f, 110f, 150f, 125f))
        val inverted = layer.snapSelection(Box(150f, 125f, 30f, 110f))

        assertEquals(straight.text, inverted.text)
        assertEquals(straight.region, inverted.region)
        assertEquals("20 4514 9154 9395", inverted.text)
    }

    /** Внешняя рамка многострочного захвата накрывает и незахваченное — рисовать надо построчно:
     *  построчные рамки не утверждают захват слова, которого в atoms нет. */
    @Test
    fun `построчные рамки не накрывают незахваченное слово между строк`() {
        val page = AtomLayer(
            listOf(
                atom("a", "Отправитель:", 10f, 100f, 150f, 120f),
                atom("e", "Индекс", 20f, 130f, 90f, 150f),
                atom("b", "Иванов", 160f, 130f, 230f, 150f),
            ),
        )

        val s = page.snapSelection(Box(140f, 118f, 170f, 132f)) // диагональ через перенос строки

        assertEquals("Отправитель: Иванов", s.text)
        assertFalse("e" in s.ids)
        // Внешняя рамка накрывает «Индекс» — потому она для кропа, не для показа.
        assertTrue(s.region.contains(50f, 140f))
        // Построчные рамки — нет: ни одна не содержит центр незахваченного слова.
        assertTrue(s.lineRegions.none { it.contains(55f, 140f) })
        assertEquals(2, s.lineRegions.size)
    }

    /** Разнесённый захват резолвится с disjoint — и для выделения это не порок: полигон назвал
     *  человек, его выбор — истина. Тест закрепляет семантику, чтобы потребитель не превратил
     *  разнесённость в предупреждение. */
    @Test
    fun `двухстрочный захват разнесённых слов резолвится с disjoint — и это законно`() {
        val s = layer.snapSelection(Box(0f, 0f, 500f, 1000f))
        val v = layer.resolve(AtomAddress.ByIds(s.ids))

        assertEquals(s.text, v.text)
        assertTrue(v.droppedIds.isEmpty())
        assertTrue(v.disjoint) // трек и слово из подвала страницы дальше порога связности
    }
}
