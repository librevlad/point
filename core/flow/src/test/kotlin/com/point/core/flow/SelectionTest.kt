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
}
