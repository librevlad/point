package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class RotatedFrameOrderTest {

    private val layer = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/table_04_names.atoms.tsv")) {
            "нет фикстуры кадра 04"
        }.bufferedReader().readText(),
    )

    @Test
    fun `слова ячейки идут в порядке строки страницы, а не задом наперёд`() {
        assertEquals(
            "Найменування продуктв\n" +
                "Karycra бтоголова свйжа\n" +
                "Onis соняшникова рафнована",
            layer.text,
        )
    }

    @Test
    fun `индекс для модели цитирует выпрямленную строку слева направо`() {
        val index = layer.promptIndex()

        assertNotNull("восемь читаемых слов обязаны дать индекс", index)
        assertEquals(
            "[w47]Найменування [w48]продуктв\n" +
                "[w62]Karycra [w63]бтоголова [w64]свйжа\n" +
                "[w73]Onis [w74]соняшникова [w75]рафнована",
            index,
        )
    }

    @Test
    fun `значение из меток собирается в порядке страницы, как бы модель их ни перечислила`() {
        val resolved = layer.resolve(AtomAddress.ByIds(listOf("w64", "w62", "w63")))

        assertEquals("Karycra бтоголова свйжа", resolved.text)
        assertFalse("слова одной ячейки — не разорванный набор", resolved.disjoint)
    }
}
