package com.point.executors

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ отвечает на два вопроса: **что произошло** и **что делать дальше** (#541).
 *
 * Сторож по исходникам (`NoJargonInProductTest` в `:app`) держит границу «чего в отказе не бывает»
 * — наших слов, имён библиотек, сырых типов файлов, кусков чужого ответа. Здесь проверено
 * положительное: что в живых отказах **есть**, и что советы зовут действия теми же именами, какие
 * человек видит на кнопках.
 *
 * Дословно, а не «непустая строка»: отказ и есть продукт этого среза, и проверка «хоть что-то
 * сказано» прошла бы на прежних «Ошибка PDF» и «Нет доступных реализаций».
 */
class RefusalSaysWhatToDoTest {

    private val image = ObjectState(ObjectKind.IMAGE)
    private val pdf = ObjectState(ObjectKind.PDF)

    @Test
    fun `отказ у PDF без текста зовёт действия их же именами`() {
        // Прежняя строка советовала «нужен OCR» — слова, которого нет ни на одной кнопке Point.
        // Совет, ведущий к несуществующей кнопке, хуже молчания: человек её ищет и не находит.
        val said = PdfRealizer.NO_TEXT_LAYER

        assertTrue(said, OcrCapability().label(image) in said)
        assertTrue(said, PagesCapability().label(pdf) in said)
    }

    @Test
    fun `отказ у PDF без текста говорит, что случилось со страницами`() {
        val said = PdfRealizer.NO_TEXT_LAYER

        assertTrue(said, "нет текста" in said)
        assertTrue("это «не с этим объектом», а не «Point сломался»", "страницы сняты картинкой" in said)
    }

    @Test
    fun `«в PDF» на чужом объекте перечисляет, с чем работает`() {
        // Отказ невосстановимый — повторять тап бессмысленно. Значит «что дальше» здесь не
        // «попробуйте ещё раз», а список объектов, на которых действие живёт.
        val said = PdfRealizer.NOT_THIS_OBJECT

        assertTrue(said, "снимок" in said && "текст" in said && "документ" in said)
        assertFalse(said, "попробуйте ещё раз" in said)
    }

    @Test
    fun `пустая цепочка исполнителей говорит про действие, а не про свои детали`() {
        // «Нет доступных реализаций» не помогало даже разработчику: он всё равно шёл смотреть код.
        val said = FallbackRealizer.NOBODY_TO_DO_IT

        assertTrue(said, "Это действие сейчас выполнить нечем" in said)
        assertTrue("нет совета, куда деваться", "выберите другое" in said)
    }

    @Test
    fun `неудавшийся скан говорит про снимок, а не про свой конвейер`() {
        val said = OpenCvScanRealizer.SCAN_FAILED

        assertTrue(said, "Страницу на снимке не удалось выпрямить" in said)
        assertTrue("нет совета, как переснять", "при ровном свете" in said)
    }

    @Test
    fun `каждый из этих отказов говорит и что случилось, и что дальше`() {
        // Общая мера для всех четырёх: сначала новость, потом выход, и разделены они тире — тем
        // же знаком, каким разделены обе половины во всех остальных отказах продукта.
        listOf(
            PdfRealizer.NO_TEXT_LAYER,
            PdfRealizer.NOT_THIS_OBJECT,
            FallbackRealizer.NOBODY_TO_DO_IT,
            OpenCvScanRealizer.SCAN_FAILED,
        ).forEach { said ->
            val halves = said.split(" — ", ". ")
            assertTrue("одна половина вместо двух: $said", halves.size >= 2)
            assertTrue("вторая половина пуста: $said", halves.last().isNotBlank())
        }
    }
}
