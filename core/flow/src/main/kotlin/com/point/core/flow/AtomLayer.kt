package com.point.core.flow

/**
 * Прямоугольник на странице в координатах кадра. Чистый Kotlin: ядро остаётся Android-free,
 * поэтому здесь нет ни `RectF`, ни `Rect`.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Атом — наименьшая адресуемая единица прочитанного: слово или его кусок с местом на странице.
 *
 * **Атом никогда не существует вне родительского объекта** (ADR-0001). Он адресуем, но не узел
 * графа: на одной странице их тысячи, и узлом становится то, что имеет смысл для человека —
 * трек-номер, а не слово.
 */
data class Atom(val id: String, val text: String, val box: Box)

/**
 * Слой улик объекта: всё, что прочитал ридер, в том виде, в каком оно лежит на странице.
 *
 * Существует, потому что адрес значения — **область, а не порядковый номер строки**. Порядок
 * строк OCR — ложь про двухколоночный документ, а 14-значный трек с посылочного экрана приходит
 * тремя кусками, и «ответ = один идентификатор» такого не выдерживает (#257, #258).
 */
class AtomLayer(val atoms: List<Atom>) {

    /**
     * Атомы, чей центроид лежит внутри [region], в исходном порядке слоя.
     *
     * Именно центроид, а не пересечение: у соседней строки край бокса заходит в область на
     * пиксель-другой на любом реальном фото, и по пересечению в значение попала бы подпись из
     * строки ниже.
     */
    fun atomsIn(region: Box): List<Atom> =
        atoms.filter { region.contains(it.box.centerX, it.box.centerY) }

    /**
     * Текст области — атомы в порядке чтения, а не в порядке выдачи ридера.
     *
     * Порядок выдачи ридером — не порядок страницы: Tesseract возвращает куски так, как их нашёл,
     * и собранный по этому порядку 14-значный трек оказывается перемешанным. Поэтому порядок
     * восстанавливается по геометрии.
     */
    fun textIn(region: Box): String =
        readingOrder(atomsIn(region)).joinToString(" ") { it.text }

    /**
     * Атомы, разложенные по строкам, и внутри строки — слева направо.
     *
     * Строка определяется **полосой**, а не равенством `top`: на реальном фото лист не идеально
     * ровный, а буквы разной высоты, поэтому верхние края слов одной строки расходятся на
     * несколько пикселей. Сортировка по `top` на таком входе переставляет куски номера местами —
     * ровно то, из-за чего 14-значный трек собирается неправильно и тихо отдаётся как валидный.
     *
     * Порог — половина высоты самого атома, а не константа в пикселях: страница может прийти в
     * любом разрешении, и абсолютный допуск, подобранный под одно фото, соврёт на другом.
     */
    fun readingOrder(subset: List<Atom> = atoms): List<Atom> = lines(subset).flatten()

    /**
     * Весь слой как текст: слова строки через пробел, строки — через перевод строки.
     *
     * Перевод строки здесь несущий, а не косметика. Это значение уедет туда, где сегодня стоит
     * `recognize(): String`, а его потребители режут результат по `\n` (`layoutOf`). Склеенная в
     * одну строку страница развалит раскладку ещё до того, как её кто-то увидит.
     */
    val text: String
        get() = lines(atoms).joinToString("\n") { line -> line.joinToString(" ") { it.text } }

    private fun lines(subset: List<Atom>): List<List<Atom>> {
        val lines = mutableListOf<MutableList<Atom>>()
        subset.sortedBy { it.box.centerY }.forEach { atom ->
            val line = lines.lastOrNull()
            val sameLine = line != null &&
                kotlin.math.abs(line.last().box.centerY - atom.box.centerY) <= atom.box.height / 2f
            if (sameLine) line.add(atom) else lines.add(mutableListOf(atom))
        }
        return lines.map { it.sortedBy { atom -> atom.box.left } }
    }
}
