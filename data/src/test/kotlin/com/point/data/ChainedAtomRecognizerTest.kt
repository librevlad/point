package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChainedAtomRecognizerTest {

    private fun layerOf(tag: String) = AtomLayer(listOf(Atom("a0", tag, Box(0f, 0f, 1f, 1f))))

    private fun reader(answer: () -> AtomLayer) = object : AtomRecognizer {
        override suspend fun read(obj: PointObject) = answer()
    }

    @Test
    fun `прочитал первый — запасной молчит`() = runTest {
        val chain = ChainedAtomRecognizer(
            reader { layerOf("местный") },
            reader { error("сюда не доходим") },
        )

        assertEquals("местный", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `первый не нашёл ничего — читает запасной`() = runTest {
        val chain = ChainedAtomRecognizer(
            reader { AtomLayer(emptyList()) },
            reader { layerOf("прежний") },
        )

        assertEquals("прежний", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `первый сорвался — это не «текста нет», очередь идёт дальше`() = runTest {
        val chain = ChainedAtomRecognizer(
            reader { error("модель не загрузилась") },
            reader { layerOf("прежний") },
        )

        assertEquals("прежний", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `частичное чтение первого не переспрашивается у запасного`() = runTest {
        val partial = AtomLayer(
            listOf(Atom("a0", "59 0017 2462 6327", Box(0f, 0f, 1f, 1f))),
            incomplete = "часть строк не разобрана",
        )
        val chain = ChainedAtomRecognizer(reader { partial }, reader { error("сюда не доходим") })

        assertEquals("59 0017 2462 6327", chain.read(pageObject).atoms.single().text)
    }

    @Test
    fun `оба молчат — названная первым причина не теряется`() = runTest {
        val chain = ChainedAtomRecognizer(
            reader { AtomLayer(emptyList(), incomplete = "not an image") },
            reader { AtomLayer(emptyList()) },
        )

        assertEquals("not an image", chain.read(pageObject).incomplete)
    }
}
