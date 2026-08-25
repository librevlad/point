package com.point.desktop

import java.io.File

/**
 * Начало текста, прочитанное для показа, и честный ответ, упёрлось ли чтение в свой предел
 * (#1086).
 *
 * Без второго поля «Показать целиком» обещало бы целиком и там, где дальше предела не
 * читали: у большого файла это неправда, и человек не узнал бы, что текст на этом не
 * кончается. Телефон считает так же — по сырому чтению, а не по показанному куску.
 */
data class TextHead(val text: String, val atLimit: Boolean)

/**
 * Куда компьютер кладёт прочитанный текст документа (#995).
 *
 * Рядом с самим документом, а не в системную временную папку: `ocr.text.ref` — постоянная
 * ссылка знания объекта, и уборка `%TEMP%` молча убила бы прочитанное. Телефон, попросивший
 * компьютер прочитать документ, получил бы ответ без текста: мёртвая ссылка в дорогу не едет.
 */
fun textBesideDocument(source: File): File =
    File(source.parentFile, source.nameWithoutExtension + " — текст.txt")

/** Читает не больше `limit` символов: окно не обязано держать в памяти файл целиком. */
fun readTextHead(file: File, limit: Int): TextHead {
    if (!file.isFile) return TextHead("", false)
    val out = StringBuilder()
    val buffer = CharArray(8192)
    file.bufferedReader().use { reader ->
        while (out.length < limit) {
            val n = reader.read(buffer)
            if (n < 0) break
            out.append(buffer, 0, minOf(n, limit - out.length))
        }
    }
    return TextHead(out.toString(), out.length >= limit)
}
