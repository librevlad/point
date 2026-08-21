package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прилипание к содержимому (ТЗ Focus, 10.08.2026): «мазнул → посмотрел на подсветку → ✓».
 * Задетое слово тянет за собой свою строку, чтобы человек не выцеливал начало и конец пальцем.
 *
 * Прилипание знает инструмент и потолок (#1037, #1039): тянет строку только кисть, обводка
 * остаётся как нарисована, а поперёк строк область не растёт выше [MAX_SNAP_GROWTH] высот
 * нарисованного — иначе одна обведённая строка превращалась в полосу через весь лист.
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

    /**
     * #1039: один высокий атом — печать, вертикальная линия, криво посаженная рамка —
     * пересекает каждую строку, через которую проходит, и все они становились «той же строкой».
     */
    @Test
    fun `высокий атом через несколько строк не раздувает мазок — прилипание отменяется и берётся нарисованное`() {
        val tall = atom("t", "|", Box(80f, 100f, 95f, 960f))
        val farBelow = atom("f", "Подпись", Box(100f, 900f, 300f, 930f))
        val layer = AtomLayer(line + below + farBelow + tall)
        val drawn = Box(90f, 204f, 430f, 226f)

        val snap = layer.snapSelection(drawn, wholeLine = true)

        assertEquals(drawn, snap.region)
        assertTrue("слова под мазком остаются текстом выделения", snap.ids.containsAll(listOf("a", "b", "c")))
        assertFalse("строка далеко внизу не должна была попасть в выделение", "f" in snap.ids)
        assertTrue(snap.region.height <= drawn.height * MAX_SNAP_GROWTH)
    }

    @Test
    fun `обводка прямоугольником не тянет строку — область остаётся как нарисована, слова под ней входят текстом`() {
        val layer = AtomLayer(line + below)
        val drawn = Box(250f, 204f, 430f, 226f)

        val brush = layer.snapSelection(drawn, wholeLine = true)
        val outline = layer.snapSelection(drawn, wholeLine = false)

        assertEquals(drawn, outline.region)
        assertTrue("кисть тянет строку до её начала", brush.region.left < outline.region.left)
        assertTrue("кисть тянет строку до её конца", brush.region.right > outline.region.right)
        assertEquals(listOf("b", "c"), outline.ids)
    }

    /** Несколько показанных мест — одно выделение: каждое прилипает по правилу своего инструмента. */
    @Test
    fun `несколько мест прилипают каждое по своему инструменту, а выделение — их объединение`() {
        val layer = AtomLayer(line + below)
        val byBrush = FocusPart(Box(250f, 204f, 430f, 226f), wholeLine = true)
        val byOutline = FocusPart(Box(120f, 305f, 200f, 325f), wholeLine = false)

        val snap = layer.snapSelection(listOf(byBrush, byOutline))

        assertEquals(listOf(Box(100f, 200f, 700f, 230f), byOutline.box), snap.parts)
        assertEquals(Box(100f, 200f, 700f, 325f), snap.region)
        assertEquals(listOf("a", "b", "c", "d"), snap.ids)
    }

    @Test
    fun `одно и то же слово, задетое дважды, в выделении одно`() {
        val layer = AtomLayer(line)
        val twice = listOf(
            FocusPart(Box(240f, 204f, 260f, 226f), wholeLine = false),
            FocusPart(Box(280f, 204f, 295f, 226f), wholeLine = false),
        )

        val snap = layer.snapSelection(twice)

        assertEquals(listOf("b"), snap.ids)
        assertEquals(2, snap.parts.size)
    }
}
