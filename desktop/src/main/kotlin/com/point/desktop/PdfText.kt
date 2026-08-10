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
 * Скан — PDF без текстового слоя. Спрашивается при приёме, а не исследованием: своего
 * цикла обогащения у компьютера нет, а знать это нужно до того, как человек увидит двери
 * (иначе «Извлечь текст» на скане заканчивается пустотой вместо честного отсутствия двери).
 * Хватает первых страниц: слой либо есть, либо его нет.
 */
fun looksScanned(pdf: PdfText, file: File): Boolean = pdf.of(file, pages = SCAN_PROBE_PAGES) == ""

private const val SCAN_PROBE_PAGES = 3
