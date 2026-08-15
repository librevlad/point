package com.point.executors

import java.io.File

/**
 * Из чего и в каком порядке собирается PDF из набора снимков (#1002).
 *
 * Человек даёт несколько снимков и ждёт документ, где страниц ровно столько же и идут они
 * так же, как лежат перед глазами. Сборка же брала файлы по кодам символов одного лишь
 * имени и молча пропускала всё, что не прочиталось: из двух снимков могла выйти одна
 * страница — и ни слова о том, куда делась вторая.
 *
 * Отбор, порядок и разговор о потере живут здесь, отдельно от рисования: их видно целиком
 * и проверить можно без Android.
 */

/** Отказ, когда страницы не из чего собирать вовсе. */
const val NO_IMAGES_FOR_PDF = "В коллекции нет изображений для PDF"

/**
 * Страницы будущего PDF: сначала папка целиком, внутри папки — по-человечески.
 *
 * Порядок задавался бы `sortedBy { name }`, и тогда `IMG_10` встаёт перед `IMG_2`, а
 * одноимённые страницы из разных папок набора перемешиваются между собой — набор
 * рассыпается ещё до того, как из него собрался документ.
 */
internal fun pdfPageOrder(dir: File): List<File> =
    dir.walkTopDown()
        .filter { it.isFile }
        .sortedWith(
            compareBy<File> { it.parentFile?.invariantSeparatorsPath.orEmpty() }
                .thenComparator { a, b -> humanOrder(a.name, b.name) },
        )
        .toList()

/**
 * Сравнение имён так, как их читает человек: числа сравниваются числами, а не посимвольно,
 * поэтому «страница 2» идёт перед «страницей 10».
 */
internal fun humanOrder(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var ai = i
            while (ai < a.length && a[ai].isDigit()) ai++
            var bj = j
            while (bj < b.length && b[bj].isDigit()) bj++
            val na = a.substring(i, ai).trimStart('0')
            val nb = b.substring(j, bj).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val byDigits = na.compareTo(nb)
            if (byDigits != 0) return byDigits
            i = ai
            j = bj
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    val byRest = (a.length - i).compareTo(b.length - j)
    return if (byRest != 0) byRest else a.compareTo(b)
}

/**
 * Что сказать человеку про собранный документ, или `null`, если сказать нечего.
 *
 * Снимок, который не прочитался, — потерянная страница, а не тихий пропуск: документ с
 * дырой выглядел успехом, и человек узнавал о пропаже, только пересчитав страницы. Файл,
 * который снимком и не был (договор в наборе рядом с фотографиями), страницей не считается
 * и отказа не вызывает.
 *
 * Отдельно: набор из одних лишь нечитаемых снимков раньше отвечал «нет изображений» —
 * неправда, изображения были, их не удалось прочитать.
 */
internal fun pdfRefusal(unread: List<String>, pages: Int): String? {
    val lost = unread.filter(::looksLikeImage)
    if (lost.isNotEmpty()) return lostPagesReason(lost)
    return if (pages == 0) NO_IMAGES_FOR_PDF else null
}

private fun lostPagesReason(lost: List<String>): String =
    "Не удалось прочитать снимки: " + lost.joinToString(", ") +
        " — в PDF не хватило бы страниц"

/** По расширению видно, что файл обязан был стать страницей. */
internal fun looksLikeImage(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "avif", "tif", "tiff",
)
