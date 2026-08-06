package com.point.executors

import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.Capability
import com.point.core.flow.Latency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Заговорившее действие не объявляется мгновенным (#288).
 *
 * `latency` — это не заметка на полях: по ней экран решает, забрать ли себе ожидание или оставить
 * работу на объекте, и она же обещает человеку, сколько ждать. Действие, которое успевает сказать
 * о себе фразу («Распаковываю архив», «Страница 4»), мгновенным не бывает — такое объявление
 * прямо противоречит тому, что делает реализатор, и раньше стоило человеку тишины: сказанные
 * слова было негде показать.
 *
 * Список ведётся руками намеренно: узнать «говорит ли реализатор» статически нельзя, а сверять
 * два соседних файла глазами — ровно тот способ, которым немота и прожила три захода среза.
 * Добавили в реализатор `reportStage` — впишите способность сюда.
 */
class SpokenWorkLatencyTest {

    private val speaking: List<Capability> = listOf(
        ScanCapability(),
        ScanPlusCapability(),
        PdfCapability(),
        WordCapability(),
        PagesCapability(),
        ArchiveCapability(),
        MergePdfCapability(),
        ScanPdfCapability(),
        CutoutCapability(),
        BlurBgCapability(),
        ReplaceBgCapability(),
        OcrCapability(),
        OfficeCapability(),
        SaveAllCapability(),
        // #224: «Читаю документ» и «Собираю бланк» — те же слова на экране, тот же счёт здесь.
        RenewPeriodCapability(),
        // #288, второй заход: «Сжать» декодирует и кодирует снимок целиком, «Дать ссылку» везёт
        // файл по сети. Оба заговорили — и «Сжать» перестало объявляться мгновенным заодно.
        ImageCapability(),
        DropLinkCapability(),
    )

    @Test
    fun `ни одно рассказывающее о себе действие не объявлено мгновенным`() {
        val instant = speaking.filter { it.meta.latency == Latency.INSTANT }.map { it.id.value }
        assertEquals(emptyList<String>(), instant)
    }

    @Test
    fun `«В Word» над фото — тот же движок, что «Распознать текст», и та же объявленная долгота`() {
        // Два пузырька стоят рядом над одним снимком и делают одну работу (Tesseract по всему
        // кадру). Разная объявленная долгота развела бы их по разным экранам: у одного ожидание
        // с отменой, у другого — притушенный список без права передумать.
        assertEquals(OcrCapability().meta.latency, WordCapability().meta.latency)
        assertEquals(Latency.SLOW, WordCapability().meta.latency)
    }
}
