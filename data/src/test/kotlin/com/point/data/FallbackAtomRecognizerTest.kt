package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Цепочка бесплатных читателей (#280) — зеркало FallbackLlmClient для геометрии. */
class FallbackAtomRecognizerTest {

    private fun layerOf(tag: String) = AtomLayer(listOf(Atom("a0", tag, Box(0f, 0f, 1f, 1f))))

    private fun cloudReader(
        name: String,
        hasKey: Boolean = true,
        takesObject: Boolean = true,
        answer: () -> AtomLayer,
    ) = object : CloudAtomRecognizer {
        override val reader = name
        override val configured = hasKey
        override fun canRead(obj: PointObject) = takesObject
        override suspend fun read(obj: PointObject) = answer()
    }

    @Test
    fun `первый непустой слой выигрывает`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("первый") { layerOf("первый") },
                cloudReader("второй") { error("сюда не доходим") },
            ),
        )
        assertEquals("первый", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `пустой слой — не победа, очередь идёт дальше`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("пустой") { AtomLayer(emptyList()) },
                cloudReader("прочитавший") { layerOf("прочитавший") },
            ),
        )
        assertEquals("прочитавший", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `402 переводит очередь к следующему слою, а не в кассу`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("платный") { error("unstructured HTTP 402") },
                cloudReader("бесплатный") { layerOf("бесплатный") },
            ),
        )
        assertEquals("бесплатный", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `429 тоже переводит очередь дальше`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("исчерпанный") { error("llamaparse HTTP 429") },
                cloudReader("свежий") { layerOf("свежий") },
            ),
        )
        assertEquals("свежий", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `слой без ключа выпадает молча, а не отказом`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("безключевой", hasKey = false) { error("сюда не доходим") },
                cloudReader("настроенный") { layerOf("настроенный") },
            ),
        )
        assertEquals("настроенный", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `ни одного настроенного слоя — просьба задать бесплатный ключ`() = runTest {
        val chain = FallbackAtomRecognizer(listOf(cloudReader("нет", hasKey = false) { layerOf("нет") }))
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertFalse(chain.available)
        assertTrue(error?.message?.contains("не настроено") == true)
        assertTrue(error?.message?.contains("бесплатный ключ") == true)
    }

    @Test
    fun `ключ есть, а входа такого сорта никто не берёт — это не «задайте ключ»`() = runTest {
        val chain = FallbackAtomRecognizer(listOf(cloudReader("только кадры", takesObject = false) { layerOf("x") }))
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // Ключ у человека задан. Совет «задайте бесплатный ключ» был бы не статусом, а
        // красивой видимостью статуса — и увёл бы отладку ровно в ту сторону, где всё в порядке.
        assertTrue(chain.available)
        assertTrue(error?.message?.contains("не берётся за этот объект") == true)
        assertFalse(error?.message?.contains("задайте") == true)
    }

    @Test
    fun `ни одного ключа — вот тогда просьба задать ключ`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(cloudReader("безключевой", hasKey = false, takesObject = false) { layerOf("x") }),
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertFalse(chain.available)
        assertTrue(error?.message?.contains("не настроено") == true)
    }

    @Test
    fun `все уперлись в лимит — отказ говорит про лимит и не предлагает платить`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("a") { error("unstructured HTTP 402") },
                cloudReader("b") { error("llamaparse HTTP 429") },
            ),
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("Бесплатные лимиты") == true)
    }

    @Test
    fun `стена сетевых ошибок схлопывается в одну строку`() = runTest {
        val chain = FallbackAtomRecognizer(
            List(4) { i -> cloudReader("p$i") { error("Unable to resolve host \"api.unstructuredapp.io\"") } },
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("нет подключения к интернету") == true)
        assertFalse(error?.message?.contains("resolve host") == true)
    }

    @Test
    fun `все отказали — честный отказ, а не пустой слой`() = runTest {
        val chain = FallbackAtomRecognizer(
            listOf(
                cloudReader("a") { AtomLayer(emptyList()) },
                cloudReader("b") { error("unstructured HTTP 500") },
            ),
        )
        val error = runCatching { chain.read(pageObject) }.exceptionOrNull()

        // Пустой слой означал бы «страница пустая» — это была бы тихая ложь.
        assertTrue(error?.message?.contains("Облачное чтение не удалось") == true)
        assertTrue(error?.message?.contains("прочитана пустой") == true)
    }
}
