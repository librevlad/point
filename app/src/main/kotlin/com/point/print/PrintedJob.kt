package com.point.print

import com.point.source.Produced

/**
 * Что родится из задания печати.
 *
 * Задание может закрыться, не отдав ни байта, — тогда объекта нет: пустой PDF в работе хуже
 * честной тишины (то же правило, что у отменённой съёмки, #246).
 */
fun printedToProduced(path: String, sizeBytes: Long): Produced? =
    if (sizeBytes > 0) Produced(path, "application/pdf") else null

/**
 * Как назвать напечатанное, чтобы человек узнал свой документ в работе.
 *
 * Разделители пути вычищаются: имя приходит от чужого приложения, и «отчёт/май» увёл бы файл из
 * своей папки — а на Android это ещё и путь наружу из песочницы.
 */
fun printedFileName(label: String?): String {
    val clean = label?.trim()?.replace(Regex("[/\\\\]"), "-").orEmpty()
    return if (clean.isEmpty()) "Печать.pdf" else "$clean.pdf"
}
