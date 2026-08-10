package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Наклейка Нова Пошта — дословный вывод устройства (#747).
 *
 * Фикстура снята с телефона: 81 слово Tesseract с их настоящей геометрией и текст самого
 * ридера. Тот текст и показал владельцу склейку — «Тарасенко Сви лана Сертвна Лумброван
 * Олександ.р» одной строкой, отправитель с получателем вперемешку.
 *
 * Правила разреза на колонки проверяются здесь, а не на сочинённой раскладке: придуманные
 * координаты подтверждают только сами себя.
 */
class RealLabelIsReadInColumnsTest {

    private val label = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/np_label.atoms.tsv")) {
            "нет фикстуры наклейки"
        }.bufferedReader().readText(),
    )

    /** Слово из левой колонки и слово из правой — по обе стороны просвета. */
    private fun sameLine(left: String, right: String): Boolean =
        label.text.lines().any { line -> left in line && right in line }

    @Test
    fun `страница читается колонками, а не поперёк`() {
        assertTrue(
            "разрез не сработал на настоящей геометрии:\n${label.text}",
            label.text.lines().size > label.readerText.orEmpty().lines().size,
        )
    }

    @Test
    fun `отправитель и получатель больше не стоят в одной строке`() {
        assertFalse(
            "склейка колонок осталась:\n${label.text}",
            sameLine("Тарасенко", "умброван"),
        )
    }

    @Test
    fun `отделение отправителя не склеено с адресом получателя`() {
        assertFalse(
            "строка идёт через всю ширину:\n${label.text}",
            sameLine("14", "Миколайович"),
        )
    }
}
