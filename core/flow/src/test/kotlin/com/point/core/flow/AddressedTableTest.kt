package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ячейка таблицы как набор меток слов страницы (#258): модель указывает, атомы дают текст.
 * Слой — тот же дословный случай, что в [ValueAddressTest]: трек `20 4514 9154 9395` тремя
 * кусками + слово в другом углу страницы.
 */
class AddressedTableTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    private val layer = AtomLayer(
        listOf(
            atom("a3", "9395", 145f, 100f, 190f, 120f),
            atom("a1", "20", 10f, 100f, 40f, 120f),
            atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
            atom("far", "Отправитель", 10f, 900f, 150f, 930f),
            atom("v1", "Олексйвка", 10f, 200f, 150f, 220f), // OCR съел «іі» — ремонтопригодно
        ),
    )

    private fun ids(vararg ids: String, text: String? = null) = CellAnswer.Ids(ids.toList(), text)

    @Test
    fun `ячейка из меток собирается из атомов в порядке чтения`() {
        val t = layer.resolveCells(listOf(listOf(ids("a3", "a1", "a2"))))

        assertEquals("20 4514 9154 9395", t.rows[0][0])
        assertTrue(t.candidates.isEmpty())
    }

    @Test
    fun `галлюцинированная метка помечает ячейку, значение собирается из настоящих`() {
        val t = layer.resolveCells(listOf(listOf(ids("a1", "ghost", "a2", "a3"))))

        assertEquals("20 4514 9154 9395⚠", t.rows[0][0])
    }

    @Test
    fun `пространственно несвязный набор помечается, а не отдаётся тихо`() {
        val t = layer.resolveCells(listOf(listOf(ids("a1", "far"))))

        assertTrue(t.rows[0][0].endsWith("⚠"))
    }

    @Test
    fun `чтение модели, совпавшее с атомами с точностью до формы, забывается`() {
        val t = layer.resolveCells(listOf(listOf(ids("a1", "a2", "a3", text = "20 4514-9154-9395"))))

        assertEquals("20 4514 9154 9395", t.rows[0][0])
        assertTrue(t.candidates.isEmpty())
    }

    @Test
    fun `модель чинит съеденные OCR буквы — ремонт принимается без пометки`() {
        val t = layer.resolveCells(listOf(listOf(ids("v1", text = "Олексіївка"))))

        assertEquals("Олексіївка", t.rows[0][0])
        assertTrue(t.candidates.isEmpty())
    }

    @Test
    fun `тронутая цифра — спор, а не ремонт — атомное чтение с пометкой, оба варианта в кандидатах`() {
        val t = layer.resolveCells(listOf(listOf(ids("a1", "a2", "a3", text = "20 4614 9154 9395"))))

        assertEquals("20 4514 9154 9395⚠", t.rows[0][0])
        assertEquals(listOf("20 4514 9154 9395", "20 4614 9154 9395"), t.candidates[0 to 0])
    }

    @Test
    fun `все метки чужие, но чтение модели есть — оно остаётся как предположение с пометкой`() {
        val t = layer.resolveCells(listOf(listOf(ids("x", "y", text = "Гречка"))))

        assertEquals("Гречка⚠", t.rows[0][0])
    }

    @Test
    fun `все метки чужие и чтения нет — ячейка пустая, без ошибки`() {
        val t = layer.resolveCells(listOf(listOf(ids("x", "y"))))

        assertEquals("", t.rows[0][0])
    }

    @Test
    fun `дословная ячейка проходит как есть — рукопись и старый контракт живут`() {
        val t = layer.resolveCells(
            listOf(listOf(CellAnswer.Literal("итого⚠"), ids("a1"))),
        )

        assertEquals("итого⚠", t.rows[0][0])
        assertEquals("20", t.rows[0][1])
    }

    @Test
    fun `маркер неуверенности в чтении модели — не спор с атомами`() {
        val t = layer.resolveCells(listOf(listOf(ids("far", text = "Отправитель⚠"))))

        assertEquals("Отправитель", t.rows[0][0])
        assertTrue(t.candidates.isEmpty())
    }

    // -- индекс слов для запроса модели --

    @Test
    fun `индекс — строки страницы, слово с меткой, порядок чтения`() {
        val index = layer.promptIndex()

        assertEquals(
            "[a1]20 [a2]4514 9154 [a3]9395\n[v1]Олексйвка\n[far]Отправитель",
            index,
        )
    }

    @Test
    fun `пустой слой не даёт индекса`() {
        assertNull(AtomLayer(emptyList()).promptIndex())
    }

    @Test
    fun `мусорное чтение не даёт индекса — рукопись модель читает своими глазами`() {
        val garbage = AtomLayer(
            (0 until 10).map { atom("g$it", "///|||", it * 20f, 0f, it * 20f + 18f, 20f) },
        )

        assertNull(garbage.promptIndex())
    }

    @Test
    fun `страница больше потолка не даёт индекса — обрезанный индекс был бы тихой потерей слов`() {
        val big = AtomLayer(
            (0 until MAX_PROMPT_ATOMS + 1).map {
                atom("w$it", "слово", (it % 30) * 40f, (it / 30) * 30f, (it % 30) * 40f + 35f, (it / 30) * 30f + 20f)
            },
        )

        assertNull(big.promptIndex())
        assertNotNull(AtomLayer(big.atoms.take(MAX_PROMPT_ATOMS)).promptIndex())
    }
}
