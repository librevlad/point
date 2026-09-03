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
 *
 * Имя документа берётся целиком, вместе с расширением. Без него «смета.xlsx» и «смета.pdf» —
 * обычная пара «книга и её выгрузка в PDF» — делили одно место: чтение второго молча
 * затирало текст первого, и у одного документа на экране показывался текст другого. Место
 * знания принадлежит одному объекту, а не имени без расширения.
 */
fun textBesideDocument(source: File): File =
    File(source.parentFile, source.name + " — текст.txt")

/**
 * Положить прочитанное рядом с документом. `null` — записать не вышло (#995, #997).
 *
 * Запись — своя работа со своей бедой, и в один `runCatching` с чтением её класть нельзя.
 * Документ на компьютере лежит там, где человек его взял: `Inbox` не копирует файл к себе, а
 * оборачивает на месте. Папка бывает только для чтения, диск — сетевым, файл — занятым Office
 * или OneDrive, места — не остаться. Документ в эту минуту цел, и отказ «документ повреждён»
 * назвал бы виноватым не того — ровно то, из-за чего заведена #997.
 */
fun keepTextBesideDocument(source: File, text: String): File? =
    runCatching { textBesideDocument(source).apply { writeText(text) } }.getOrNull()

/**
 * Орган компьютера для общего чтения офисного документа (#1379): текст ложится рядом с
 * документом, а не в `%TEMP%`, который подметает операционная система (#995).
 */
val PcTextBesideDocument = com.point.core.flow.TextKeeper { source, text ->
    keepTextBesideDocument(File(source.uri.value), text)?.absolutePath
}

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
