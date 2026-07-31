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

    /** «Указала в никуда» и «честно не разобрано» — разные вещи (ревью #281): пустая ячейка
     *  с отброшенными метками несёт ⚠, иначе разрыв связи модель↔страница неотличим от пустоты. */
    @Test
    fun `все метки чужие и чтения нет — ячейка помечена, а не тихо пустая`() {
        val t = layer.resolveCells(listOf(listOf(ids("x", "y"))))

        assertEquals("⚠", t.rows[0][0])
    }

    @Test
    fun `дословная ячейка без цифр проходит как есть — рукопись и старый контракт живут`() {
        val t = layer.resolveCells(
            listOf(listOf(CellAnswer.Literal("итого⚠"), ids("a1"))),
        )

        assertEquals("итого⚠", t.rows[0][0])
        assertEquals("20", t.rows[0][1])
    }

    /** Диктовка мимо страницы (ревью #281): слой жив, а продиктованной цифры в нём нет нигде —
     *  ровно та подмена, которую промпт лишь просил не делать, а код теперь помечает. */
    @Test
    fun `дословная цифра, которой нет на странице, помечается как диктовка`() {
        val t = layer.resolveCells(listOf(listOf(CellAnswer.Literal("1600"))))

        assertEquals("1600⚠", t.rows[0][0])
    }

    @Test
    fun `дословная цифра, совпавшая со страницей, проходит чистой`() {
        val t = layer.resolveCells(listOf(listOf(CellAnswer.Literal("4514 9154"))))

        assertEquals("4514 9154", t.rows[0][0])
    }

    /** Спор длиннее лимита дропдауна: вариантов не остаётся — пометка живёт, пустой список нет. */
    @Test
    fun `спор о длинном чтении не рождает пустой дропдаун`() {
        val long = AtomLayer(listOf(atom("l1", "9".repeat(85), 10f, 10f, 900f, 30f)))
        val t = long.resolveCells(listOf(listOf(CellAnswer.Ids(listOf("l1"), "8" + "9".repeat(84)))))

        assertTrue(t.rows[0][0].endsWith("⚠"))
        assertTrue(t.candidates.isEmpty())
    }

    @Test
    fun `маркер неуверенности в чтении модели — не спор с атомами`() {
        val t = layer.resolveCells(listOf(listOf(ids("far", text = "Отправитель⚠"))))

        assertEquals("Отправитель", t.rows[0][0])
        assertTrue(t.candidates.isEmpty())
    }

    // -- индекс слов для запроса модели --

    /** Куски трека несут улику правила прямо в метке — модель видит подсказку там, где отвечает. */
    @Test
    fun `индекс — строки страницы, слово с меткой, улики правил атрибутом`() {
        val index = layer.promptIndex()

        assertEquals(
            "[a1 rule=track-shaped]20 [a2 rule=track-shaped]4514 9154 [a3 rule=track-shaped]9395\n" +
                "[v1]Олексйвка\n[far]Отправитель",
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
