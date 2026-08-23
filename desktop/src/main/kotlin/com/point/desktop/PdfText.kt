package com.point.desktop

import java.io.File

/**
 * Текст из PDF на компьютере (#631). Телефон это давно умеет тем же PDFBox — расхождение
 * поверхностей читалось как «это разные программы» (`docs/DESKTOP-CONTRACT.md`).
 */
fun interface PdfText {

    /** `null` — прочитать не вышло; пустая строка — текстового слоя нет (скан). */
    fun of(file: File, pages: Int?): String?
}

fun PdfText.of(file: File): String? = of(file, null)

class PdfBoxText : PdfText {

    override fun of(file: File, pages: Int?): String? = runCatching {
        org.apache.pdfbox.pdmodel.PDDocument.load(file).use { document ->
            val stripper = org.apache.pdfbox.text.PDFTextStripper()
            if (pages != null) {
                stripper.startPage = 1
                stripper.endPage = pages
            }
            stripper.getText(document).trim()
        }
    }.getOrNull()
}

/**
 * Скан — PDF, из которого текст не достаётся файлом. Спрашивается при приёме, а не
 * исследованием: своего цикла обогащения у компьютера нет, а знать это нужно до того, как
 * человек увидит двери (иначе «Извлечь текст» на скане заканчивается пустотой вместо честного
 * отсутствия двери). Хватает первых страниц: слой либо есть, либо его нет.
 *
 * Нечитаемый слой считается тем же самым (#933, #995): у документа своя раскладка шрифта,
 * кириллица лежит под латинскими кодами, и «извлечённый» текст — мусор. Такой документ читают
 * страницами, а не файлом, и дверь ему нужна та же, что скану.
 */
fun looksScanned(pdf: PdfText, file: File): Boolean {
    val probe = pdf.of(file, pages = SCAN_PROBE_PAGES) ?: return false
    return probe.isEmpty() || com.point.core.flow.ReadableText.unreadable(probe)
}

private const val SCAN_PROBE_PAGES = 3
