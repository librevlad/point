package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Адрес значения — множество атомов, не точка (#258). Дословный случай — трек
 * `20 4514 9154 9395` с посылочного экрана, который движок отдал тремя кусками: контракт
 * «ответ модели = один идентификатор» такого не выдерживает, номер собирался неправильно и
 * тихо отдавался как валидный.
 *
 * Валидация id против индекса — обязательная часть контракта, не оптимизация (блокер Gemini
 * из финального раунда design v3): модель может сгаллюцинировать несуществующий id или склеить
 * пространственно несовместимые куски, и связь ответа с координатами порвётся незаметно.
 */
class ValueAddressTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    /** Три куска дословного трека в одной строке + подпись в другом углу страницы. */
    private val layer = AtomLayer(
        listOf(
            atom("a3", "9395", 145f, 100f, 190f, 120f),
            atom("a1", "20", 10f, 100f, 40f, 120f),
            atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
            atom("far", "Отправитель", 10f, 900f, 150f, 930f),
        ),
    )

    @Test
    fun `значение по набору id читается из атомов в порядке чтения, не в порядке перечисления`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("a3", "a1", "a2")))

        assertEquals("20 4514 9154 9395", v.text)
        assertTrue(v.droppedIds.isEmpty())
        assertFalse(v.disjoint)
    }

    @Test
    fun `галлюцинированный id отбрасывается видимо, значение собирается из настоящих`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("a1", "ghost-7", "a2", "a3")))

        assertEquals("20 4514 9154 9395", v.text)
        assertEquals(listOf("ghost-7"), v.droppedIds)
    }

    @Test
    fun `адрес целиком из неизвестных id даёт пустое значение, а не ошибку`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("x", "y")))

        assertEquals("", v.text)
        assertEquals(listOf("x", "y"), v.droppedIds)
        assertTrue(v.atoms.isEmpty())
    }

    /** Склейка пространственно несовместимых кусков — порванная связь ответа с координатами.
     *  Значение собирается (улика не уничтожается), но помечено: это предположение, не факт. */
    @Test
    fun `пространственно несовместимый набор помечается, а не отдаётся тихо как валидный`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("a1", "far")))

        assertTrue(v.disjoint)
    }

    @Test
    fun `смежные куски одной строки связны`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("a1", "a2", "a3")))

        assertFalse(v.disjoint)
    }

    @Test
    fun `адрес областью делегирует резолверу по центроиду`() {
        val v = layer.resolve(AtomAddress.ByRegion(Box(0f, 95f, 200f, 125f)))

        assertEquals("20 4514 9154 9395", v.text)
        assertTrue(v.droppedIds.isEmpty())
        assertFalse(v.disjoint)
    }

    /** Дубли в адресе не раздувают значение: модель любит перечислить один id дважды. */
    @Test
    fun `повторённый id считается один раз`() {
        val v = layer.resolve(AtomAddress.ByIds(listOf("a1", "a1", "a2", "a3")))

        assertEquals("20 4514 9154 9395", v.text)
    }
}
