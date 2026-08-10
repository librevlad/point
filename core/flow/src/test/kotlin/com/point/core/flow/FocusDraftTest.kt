package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus как инструмент (ТЗ владельца 10.08.2026): мазок — это намерение, а не рисунок.
 * «Кисть не должна быть буквально кистью»: Point превращает мазок в аккуратную область,
 * слегка прилипая к ближайшему содержимому, — попадать идеально не нужно.
 */
class FocusDraftTest {

    /** Строка документа: «Рахунок IBAN: UA93…» — три слова подряд на одной высоте. */
    private val line = listOf(
        atom("a", "Рахунок", Box(100f, 200f, 220f, 230f)),
        atom("b", "IBAN:", Box(230f, 200f, 300f, 230f)),
        atom("c", "UA9330529900002600", Box(310f, 200f, 700f, 230f)),
    )

    /** Соседняя строка ниже — под мазок не попадает и прилипанием не захватывается. */
    private val below = atom("d", "Банк: ПРИВАТБАНК", Box(100f, 300f, 500f, 330f))

    private fun atom(id: String, text: String, box: Box) = Atom(id = id, text = text, box = box)

    private fun layer(vararg atoms: Atom) = AtomLayer(atoms.toList())

    private fun stroke(vararg points: Pair<Float, Float>, width: Float = 20f, erase: Boolean = false) =
        FocusStroke(points.map { FocusPoint(it.first, it.second) }, width = width, erase = erase)

    @Test
    fun `пустой черновик не даёт области`() {
        assertNull(FocusDraft().region())
    }

    @Test
    fun `мазок без содержимого рядом становится своим прямоугольником с запасом`() {
        val draft = FocusDraft().add(stroke(120f to 210f, 600f to 215f))

        val region = draft.region(pad = 0f)!!

        assertEquals(110f, region.left, 0.5f)
        assertEquals(610f, region.right, 0.5f)
        assertTrue("толщина кисти обязана войти в область", region.height >= 20f)
    }

    @Test
    fun `ластик убирает то, что задел, и не трогает остальное`() {
        val draft = FocusDraft()
            .add(stroke(120f to 210f, 200f to 210f))
            .add(stroke(120f to 310f, 200f to 310f))
            .add(stroke(150f to 310f, width = 30f, erase = true))

        val region = draft.region(pad = 0f)!!

        assertTrue("нижний мазок обязан был стереться: " + region, region.bottom <= 230f)
    }

    @Test
    fun `отменённое возвращается, а новый мазок обрывает возврат`() {
        val one = FocusDraft().add(stroke(120f to 210f, 200f to 210f))
        val two = one.add(stroke(120f to 310f, 200f to 310f))

        val undone = two.undo()
        assertTrue(undone.canRedo)
        assertEquals(one.strokes, undone.strokes)

        val back = undone.redo()
        assertEquals(two.strokes, back.strokes)

        val fresh = undone.add(stroke(400f to 400f, 500f to 400f))
        assertFalse("после нового мазка возвращать нечего", fresh.canRedo)
    }

    @Test
    fun `очистка убирает всё, но её саму можно отменить`() {
        val drawn = FocusDraft().add(stroke(120f to 210f, 200f to 210f))

        val empty = drawn.cleared()

        assertNull(empty.region())
        assertTrue(empty.canUndo)
        assertEquals(drawn.strokes, empty.undo().strokes)
    }

    @Test
    fun `область не выходит за края страницы`() {
        val draft = FocusDraft().add(stroke(10f to 10f, 60f to 10f, width = 40f))

        val region = draft.region(pad = 20f, page = Box(0f, 0f, 800f, 600f))!!

        assertEquals(0f, region.left, 0.01f)
        assertEquals(0f, region.top, 0.01f)
    }
}
