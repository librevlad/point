package com.point.executors

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalSaysWhatToDoTest {

    private val image = ObjectState(ObjectKind.IMAGE)
    private val pdf = ObjectState(ObjectKind.PDF)
    private val presentation = ObjectState(ObjectKind.OFFICE, setOf(com.point.core.model.Feature.IS_PRESENTATION))

    /**
     * Совета «разложите на страницы, потом распознайте» больше нет (#1257, #995): назван один
     * шаг, который у документа действительно есть, — «Прочитать документ».
     */
    @Test
    fun `отказ у PDF без текста зовёт один существующий шаг, а не два`() {
        val said = com.point.core.flow.capabilities.NO_READABLE_PDF_LAYER

        assertTrue(said, ReadDocumentCapability().label(pdf) in said)
        assertTrue(said, PagesCapability().label(pdf) !in said)
        assertTrue(said, OcrCapability().label(image) !in said)
    }

    /**
     * Названный шаг обязан быть у этого документа на месте, иначе совет ведёт в пустоту.
     *
     * Отказ и дверь выведены из одного правила `pdfLayerUnusable`: по нему же ставится
     * признак «текст файлом не достаётся» — исследованием `pdf-image-shape` на телефоне и
     * приёмом на компьютере. Слой, на котором «Извлечь текст» отказывает, — ровно тот слой,
     * из-за которого документ получает признак, а с ним и дверь «Прочитать документ».
     */
    @Test
    fun `шаг, который называет отказ, у такого документа есть`() {
        assertTrue(
            "правило отказа не считает такой слой негодным — тогда отказа и не будет",
            com.point.core.flow.pdfLayerUnusable(GARBLED),
        )

        val named = ObjectState(ObjectKind.PDF, setOf(com.point.core.model.Feature.IS_IMAGE_PDF))
        assertTrue("названной двери у такого документа нет", ReadDocumentCapability().accepts(named))
        assertFalse(
            "быстрая дверь осталась там, где текста в файле нет",
            com.point.core.flow.capabilities.PdfCapability().accepts(named),
        )
    }

    /**
     * Отказ офисного документа называет тот формат, который принесли (#997).
     *
     * Современная .xlsx слышала про старые .doc и .xls — причину, которая к ней не относится.
     */
    @Test
    fun `отказ офисного документа не валит вину на чужой формат`() {
        assertTrue(
            com.point.core.flow.NO_TEXT_IN_OFFICE,
            ".doc" !in com.point.core.flow.NO_TEXT_IN_OFFICE && ".xls" !in com.point.core.flow.NO_TEXT_IN_OFFICE,
        )
        assertTrue(
            com.point.core.flow.OLD_OFFICE_FORMAT,
            ".xlsx" in com.point.core.flow.OLD_OFFICE_FORMAT && ".docx" in com.point.core.flow.OLD_OFFICE_FORMAT,
        )
    }

    @Test
    fun `«в PDF» на чужом объекте перечисляет, с чем работает`() {

        val said = PdfRealizer.NOT_THIS_OBJECT

        assertTrue(said, "снимок" in said && "текст" in said && "документ" in said)
        assertFalse(said, "попробуйте ещё раз" in said)
    }

    @Test
    fun `пустая цепочка исполнителей говорит про действие, а не про свои детали`() {

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

    /**
     * Человек нажал «Слайды» — и слышит про слайды (#1105, §13).
     *
     * Колода из одних картинок получала «В этом документе текста нет»: ответ про текст на
     * вопрос про слайды, да ещё и отменяющий сами слайды — они-то есть. Названная дверь у
     * презентации при этом на месте: «Открыть» принимает любой файловый объект.
     */
    @Test
    fun `отказ «Слайдов» говорит про слайды и зовёт дверь, которая у презентации есть`() {
        val said = SlidesRealizer.NO_WORDS_ON_SLIDES

        assertTrue(said, "лайд" in said)
        assertFalse("это ответ про текст, а не про слайды", said == com.point.core.flow.NO_TEXT_IN_OFFICE)
        listOf(said, SlidesRealizer.NOT_SPLIT).forEach {
            assertTrue("отказ не зовёт никуда: $it", "откройте" in it.lowercase())
        }
        assertTrue("названной двери у презентации нет", OpenCapability().accepts(presentation))
    }

    @Test
    fun `каждый из этих отказов говорит и что случилось, и что дальше`() {

        listOf(
            com.point.core.flow.capabilities.NO_READABLE_PDF_LAYER,
            com.point.core.flow.NO_TEXT_IN_OFFICE,
            com.point.core.flow.OLD_OFFICE_FORMAT,
            SlidesRealizer.NOT_SPLIT,
            SlidesRealizer.NO_WORDS_ON_SLIDES,
            PdfRealizer.PDF_FAILED,
            PdfRealizer.NOT_THIS_OBJECT,
            FallbackRealizer.NOBODY_TO_DO_IT,
            OpenCvScanRealizer.SCAN_FAILED,
        ).forEach { said ->
            val halves = said.split(" — ", ". ")
            assertTrue("одна половина вместо двух: $said", halves.size >= 2)
            assertTrue("вторая половина пуста: $said", halves.last().isNotBlank())
        }
    }

    private companion object {

        /** Слой украинского бухгалтерского PDF с подменённой раскладкой шрифта (#933). */
        const val GARBLED =
            "ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo BaxraxoorpxMyBaq cKnaAaHHR " +
                "flocraqanbHHK e.qPnov Eniqgxtp 3aMoBHHK PaxyHok-cbakrypa"
    }
}
