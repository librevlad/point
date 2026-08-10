package com.point.core.flow

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Порядок «сначала на диск, потом подтверждение» держится только тем, что письмо
 * действительно переживает падение (#680).
 */
class KeptLettersTest {

    @get:Rule val tmp = TemporaryFolder()

    private val first = "00000000000000000001-aa"
    private val second = "00000000000000000002-bb"

    @Test
    fun `сохранённое письмо переживает падение и ждёт следующего запуска`() {
        val dir = tmp.newFolder()
        KeptLetters(dir).keep(first, "запечатанное".toByteArray())

        val afterCrash = KeptLetters(dir)

        assertEquals(listOf(first), afterCrash.waiting())
        assertArrayEquals("запечатанное".toByteArray(), afterCrash.blob(first))
    }

    @Test
    fun `разобранное письмо больше не ждёт`() {
        val letters = KeptLetters(tmp.newFolder())
        letters.keep(first, "запечатанное".toByteArray())

        letters.done(first)

        assertEquals(emptyList<String>(), letters.waiting())
        assertNull(letters.blob(first))
    }

    @Test
    fun `письма ждут в том порядке, в каком пришли`() {
        val letters = KeptLetters(tmp.newFolder())

        letters.keep(second, "второе".toByteArray())
        letters.keep(first, "первое".toByteArray())

        assertEquals(listOf(first, second), letters.waiting())
    }

    @Test
    fun `письмо, которое валит разбор раз за разом, откладывается, но остаётся на диске`() {
        val letters = KeptLetters(tmp.newFolder(), tries = 2)
        letters.keep(first, "запечатанное".toByteArray())

        assertEquals(1, letters.tried(first))
        assertEquals("вторая попытка ещё положена", listOf(first), letters.waiting())
        assertEquals(2, letters.tried(first))

        assertEquals("круг падений остановлен", emptyList<String>(), letters.waiting())
        assertNotNull("объект человека не выброшен", letters.blob(first))
    }

    @Test
    fun `повторная доставка того же письма не начинает счёт попыток заново`() {
        val letters = KeptLetters(tmp.newFolder(), tries = 2)
        letters.keep(first, "запечатанное".toByteArray())
        letters.tried(first)

        letters.keep(first, "запечатанное".toByteArray())

        assertEquals(2, letters.tried(first))
    }

    @Test
    fun `недописанное письмо не считается принятым`() {
        val dir = tmp.newFolder()
        val letters = KeptLetters(dir)
        letters.keep(first, "запечатанное".toByteArray())

        File(dir, "$second.part").writeText("оборвалось на середине")

        assertEquals(listOf(first), letters.waiting())
    }

    @Test
    fun `имя письма с сервера не уводит запись из своей папки`() {
        val dir = tmp.newFolder()
        val letters = KeptLetters(dir)

        letters.keep("../чужое", "запечатанное".toByteArray())

        assertTrue(
            "запись ушла наружу: " + (dir.parentFile.list()?.toList() ?: emptyList<String>()),
            dir.parentFile.listFiles().orEmpty().none { it.name.contains("чужое") },
        )
    }
}
