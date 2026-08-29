package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Слова, прочитанные на выбеленной копии, живут в координатах снимка (#1046).
 *
 * Копия уменьшена: выбеливать снимок в полный размер незачем. Но подсветка найденного,
 * `at.region` у знания и вырезка ячейки считают по снимку человека — и промахнутся ровно на
 * тех кадрах, ради которых второй заход и делался.
 */
class PaperWhiteningTest {

    private fun layer(box: Box, transform: FrameTransform? = null) = AtomLayer(
        listOf(Atom("w0", "145", box, 0.9f, "tesseract", "5.3", 0)),
        readerText = "АКТ № 145",
        transform = transform,
    )

    @Test
    fun `слова с уменьшенной копии переносятся на снимок`() {
        val moved = layer(Box(10f, 20f, 60f, 40f)).inSourceFrame(shrink = 2)

        assertEquals(Box(20f, 40f, 120f, 80f), moved.atoms.single().box)
    }

    @Test
    fun `копия в натуральную величину слой не трогает`() {
        val read = layer(Box(10f, 20f, 60f, 40f))

        assertSame(read, read.inSourceFrame(shrink = 1))
    }

    /**
     * Обратный ход к прочитанному кадру считает по тому же множителю: иначе высота строки,
     * по которой решают, увеличивать ли кадр на следующем заходе, оказалась бы вдвое чужой.
     */
    @Test
    fun `перевод обратно к прочитанному кадру остаётся верным`() {
        val onCopy = FrameTransform(sample = 1, uprightWidth = 1000, uprightHeight = 750)
        val read = layer(Box(10f, 20f, 60f, 40f), onCopy)

        val moved = read.inSourceFrame(shrink = 4)

        assertEquals(read.atoms.single().box, moved.transform!!.toUpright(moved.atoms.single().box))
    }

    /** Повёрнутый кадр переносится так же: поворот копии остаётся при копии. */
    @Test
    fun `поворот прочитанного кадра переносом не теряется`() {
        val onCopy = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 1000, uprightHeight = 750)
        val moved = layer(Box(10f, 20f, 60f, 40f), onCopy).inSourceFrame(shrink = 2)

        assertEquals(90, moved.transform?.rotationDegrees)
        assertEquals(1000, moved.transform?.uprightWidth)
    }

    /**
     * И обратный ход у повёрнутого кадра остаётся верным.
     *
     * Снимок с рук почти всегда повёрнут меткой камеры, то есть это не редкий случай, а
     * обычный: копия лежит так же, как файл, и перевод «к прочитанному кадру» обязан после
     * переноса возвращать ровно ту же рамку, что и до него.
     */
    @Test
    fun `перевод обратно верен и у повёрнутого кадра`() {
        val onCopy = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 1000, uprightHeight = 750)
        val read = layer(Box(10f, 20f, 60f, 40f), onCopy)

        val moved = read.inSourceFrame(shrink = 4)

        assertEquals(
            onCopy.toUpright(read.atoms.single().box),
            moved.transform!!.toUpright(moved.atoms.single().box),
        )
    }

    /** Названная движком причина едет вместе со слоем, а не теряется при переносе. */
    @Test
    fun `причина неполного чтения переезжает вместе со словами`() {
        val partial = AtomLayer(
            listOf(Atom("w0", "145", Box(1f, 1f, 2f, 2f))),
            incomplete = INCOMPLETE_TIMEOUT,
        )

        assertEquals(INCOMPLETE_TIMEOUT, partial.inSourceFrame(shrink = 2).incomplete)
    }
}
