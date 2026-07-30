package com.point.core.flow

/**
 * Адрес значения на странице — **множество атомов, не точка** (#258).
 *
 * Первая версия контракта звучала «ответ модели = идентификатор элемента раскладки» и не
 * выдержала фрагментации: 14-значный трек распадается на три атома, номер собирался неправильно
 * и тихо отдавался как валидный. Адрес — либо область ([ByRegion]), либо набор спанов от модели
 * ([ByIds]).
 */
sealed interface AtomAddress {
    /** Область страницы: значение — атомы, чей центроид внутри (см. [AtomLayer.atomsIn]). */
    data class ByRegion(val region: Box) : AtomAddress

    /** Набор id атомов, названный моделью. Валидируется против индекса слоя при резолве. */
    data class ByIds(val ids: List<String>) : AtomAddress
}

/**
 * Разрешённое значение. Символы значения читаются **из атомов** ([text] — порядок чтения по
 * геометрии), никогда из текста модели: у этого типа просто нет входа, куда модельный текст
 * можно было бы подать.
 *
 * Порванная связь ответа с координатами видима, не молчалива:
 * - [droppedIds] — id, которых в слое нет (галлюцинация модели); отброшены и перечислены;
 * - [disjoint] — набор пространственно несовместим (куски из разных углов страницы склеены в
 *   одно «значение»). Улика не уничтожается — значение собрано, но помечено как предположение.
 */
data class ResolvedValue(
    val atoms: List<Atom>,
    val text: String,
    val droppedIds: List<String> = emptyList(),
    val disjoint: Boolean = false,
)

/**
 * Резолвер адреса. Валидация против индекса — обязательная часть контракта, не оптимизация
 * (блокер Gemini, design v3): неизвестный id отбрасывается видимо, набор проверяется на
 * связность. Чистый код, нулевая латентность.
 */
fun AtomLayer.resolve(address: AtomAddress): ResolvedValue = when (address) {
    is AtomAddress.ByRegion -> {
        val hit = atomsIn(address.region)
        ResolvedValue(hit, textIn(address.region))
    }
    is AtomAddress.ByIds -> {
        val index = atoms.associateBy { it.id }
        val wanted = address.ids.distinct() // модель любит перечислить один id дважды
        val found = wanted.mapNotNull { index[it] }
        val ordered = readingOrder(found)
        ResolvedValue(
            atoms = ordered,
            text = ordered.joinToString(" ") { it.text },
            droppedIds = wanted.filter { it !in index },
            disjoint = !isConnected(ordered),
        )
    }
}

/**
 * Пространственная связность набора: каждый атом достижим от любого другого по шагам «до соседа
 * ближе, чем [NEIGHBOUR_HEIGHTS] его высот» (по центроидам). Куски одного значения — соседи по
 * строке или соседним строкам; «трек из левого верхнего угла + слово из подвала» одним значением
 * быть не может, и склейка таких кусков — ровно та порванная связь, которую валидация ловит.
 *
 * Порог в высотах атома, не в пикселях: страница приходит в любом разрешении, и абсолютный
 * допуск, подобранный под одно фото, соврёт на другом (тот же принцип, что полоса строки в
 * [AtomLayer.readingOrder]).
 */
private fun isConnected(atoms: List<Atom>): Boolean {
    if (atoms.size <= 1) return true
    val visited = HashSet<Int>()
    val queue = ArrayDeque<Int>().apply { add(0) }
    visited += 0
    while (queue.isNotEmpty()) {
        val cur = atoms[queue.removeFirst()]
        atoms.forEachIndexed { i, other ->
            if (i !in visited && near(cur, other)) {
                visited += i
                queue.add(i)
            }
        }
    }
    return visited.size == atoms.size
}

private fun near(a: Atom, b: Atom): Boolean {
    val dx = a.box.centerX - b.box.centerX
    val dy = a.box.centerY - b.box.centerY
    val reach = maxOf(a.box.height, b.box.height) * NEIGHBOUR_HEIGHTS +
        (a.box.right - a.box.left + b.box.right - b.box.left) / 2f
    return dx * dx + dy * dy <= reach * reach
}

/** Радиус соседства в высотах атома — щедрый: куски номера разделены пробелами и переносом
 *  строки, но не половиной страницы. */
private const val NEIGHBOUR_HEIGHTS = 3f
