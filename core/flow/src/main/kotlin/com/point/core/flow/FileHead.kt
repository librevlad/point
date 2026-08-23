package com.point.core.flow

import java.io.File

/**
 * Первые [limit] символов файла; не файл или не прочитался — пустая строка.
 *
 * Начало файла спрашивают все, кому нужно содержимое, а не весь объект: чтение текста для
 * человека и адрес ссылки при рождении объекта (#999). Читается ровно столько, сколько
 * попросили, — весь файл в память не поднимается.
 */
fun fileHead(path: String, limit: Int): String = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching ""

    val out = StringBuilder()
    val buffer = CharArray(8192)
    file.bufferedReader().use { reader ->
        while (out.length < limit) {
            val n = reader.read(buffer)
            if (n < 0) break
            out.append(buffer, 0, minOf(n, limit - out.length))
        }
    }
    out.toString()
}.getOrDefault("")
