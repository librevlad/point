package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Прилипание к содержимому (ТЗ Focus, 10.08.2026): «мазнул → посмотрел на подсветку → ✓».
 * Задетое слово тянет за собой свою строку, чтобы человек не выцеливал начало и конец пальцем.
 */
class FocusSnapsToLineTest {

    private val line = listOf(
        atom("a", "Рахунок", Box(100f, 200f, 220f, 230f)),
        atom("b", "IBAN:", Box(230f, 200f, 300f, 230f)),
        atom("c", "UA9330529900002600", Box(310f, 200f, 700f, 230f)),
    )

    private val below = atom("d", "Банк", Box(100f, 300f, 500f, 330f))

    /** Второй столбец справа: далеко, и продолжением строки не является. */
    private val otherColumn = atom("e", "1530 грн", Box(900f, 200f, 1000f, 230f))

    private fun atom(id: String, text: String, box: Box) = Atom(id = id, text = text, box = box)

    @Test
    fun `небрежный мазок по середине строки берёт строку целиком`() {
        val layer = AtomLayer(line + below)

        val snap = layer.snapSelection(Box(250f, 204f, 430f, 226f), wholeLine = true)

        assertEquals(100f, snap.region.left, 0.5f)
        assertEquals(700f, snap.region.right, 0.5f)
        assertEquals("захвачена лишняя строка снизу", 230f, snap.region.bottom, 0.5f)
        assertEquals("Рахунок IBAN: UA9330529900002600", snap.text)
    }

    @Test
    fun `мазок через две строки берёт обе целиком`() {
        val layer = AtomLayer(line + below)

        val snap = layer.snapSelection(Box(290f, 204f, 310f, 316f), wholeLine = true)

        assertEquals(100f, snap.region.left, 0.5f)
        assertEquals(700f, snap.region.right, 0.5f)
        assertEquals(330f, snap.region.bottom, 0.5f)
    }

    @Test
    fun `соседний столбец не приклеивается — разрыв слишком широк`() {
        val layer = AtomLayer(line + otherColumn)

        val snap = layer.snapSelection(Box(250f, 204f, 430f, 226f), wholeLine = true)

        assertEquals("подхвачен чужой столбец", 700f, snap.region.right, 0.5f)
    }

    @Test
    fun `под мазком нет ни слова — область остаётся такой, как показал человек`() {
        val layer = AtomLayer(line)

        val snap = layer.snapSelection(Box(100f, 900f, 200f, 950f), wholeLine = true)

        assertEquals(Box(100f, 900f, 200f, 950f), snap.region)
        assertEquals("", snap.text)
    }
}
