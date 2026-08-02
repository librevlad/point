package com.point.core.flow

/**
 * The reconciled table from several independent model reads (#200 ocr++). [rows] is the merged table —
 * plurality value per cell, with a trailing ⚠ on any cell the models disagreed on; [candidates] holds
 * the distinct readings for each disagreed `(row, col)` so the UI can offer them for one-tap picking.
 */
data class Consensus(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>>,
    /**
     * Сколько чтений реально стоит за таблицей. `1` при нескольких прочтениях значит, что они
     * говорили о разных таблицах и смешивать их было нельзя, — и человеку это надо сказать:
     * иначе он получает одно ничем не подтверждённое чтение с видом проверенного.
     *
     * Замер (кадр 23, повторы 02.08.2026) показал, зачем: одно и то же чтение одной и той же
     * моделью выходит то 48 из 49 верных ячеек, то 23 из 49 — разброс больше любой разницы
     * между конфигурациями. Второе мнение защищает не от плохой модели, а от плохого раза;
     * когда защиты не случилось, молчать об этом нельзя.
     */
    val sources: Int = 1,
)

/**
 * Vote each cell across [tables] (independent reads of the same table). A cell is clean iff every
 * present read agrees; otherwise it takes the plurality raw value, is flagged ⚠, and its distinct
 * readings become candidates.
 *
 * Что с чем сравнивать, решает **содержимое, а не индекс** — и по строкам ([alignRows]), и по
 * ячейкам внутри строки ([columnsOf]). Индекс врёт ровно там, где чтения расходятся: модель,
 * пропустившая шапку, сдвинута целиком, а модель, пропустившая узкий столбец бланка, — на
 * столбец внутри каждой строки. Голосуя по индексу, обе превращали соседнее значение в «другое
 * чтение» — и на эталонной ведомости владельца ⚠ стояло на 387 ячейках из ~430.
 *
 * **Отсутствие — не согласие.** Строку, которой у второго чтения нет вовсе, голосование
 * объявляло единогласной: спорить не с кем. На ведомости владельца так прошли молча
 * тринадцать строк, которых на снимке нет вообще («Паштет м'ясний», «Йогурт», артикулы из
 * четырёх цифр вместо пяти) — одна модель дописала правдоподобное продолжение, и оно легло
 * в файл наравне с прочитанным. Теперь такая строка помечается: не выброшена (второе чтение
 * могло её и правда пропустить — терять прочитанное молча нельзя), но и не выдана за
 * подтверждённую. Вариантов у неё нет — выбирать не из чего, поэтому дропдаун не заводится.
 */
fun reconcile(tables: List<List<List<String>>>): Consensus {
    val ts = tables.filter { it.isNotEmpty() }
    if (ts.size <= 1) return Consensus(ts.firstOrNull() ?: emptyList(), emptyMap(), sources = ts.size)

    // Строки выравниваются ПО СОДЕРЖИМОМУ, а не по индексу (#294): модель, пропустившая
    // строку заголовка, сдвинута целиком, и голосование по индексу сравнивало её заголовок
    // со значением соседа — ложный ⚠ на каждой ячейке и дропдауны из разнородных чтений.
    val slots = alignRows(ts)

    // Чтения не сошлись — не смешиваем. Смесь двух рассказов о разных таблицах не становится
    // правдой оттого, что строки сложены в один файл: на живом прогоне ведомости так вышло
    // 57 строк вместо 35 и пометка на 75% ячеек — человек получил не таблицу, а задание
    // перепроверить её целиком. Честнее отдать одно чтение целиком: оно про одну страницу.
    if (slots.size >= MIN_SLOTS_TO_JUDGE_ALIGNMENT && agreedShare(slots, ts.size) < MIN_ALIGNED_SHARE) {
        // Кого оставить — решает согласие, а не очередь провайдеров. Замер повторов показал
        // почему: у одной и той же модели чтение выходит то 48 верных ячеек из 49, то 23, —
        // и «первое по списку» с равной вероятностью оказывается любым из двух. Чтение,
        // которое ближе всех к остальным, — не обязательно лучшее, но это единственный
        // довод, который у нас есть без эталона. При двух чтениях доводов нет вовсе, и
        // порядок провайдеров (сильнейший первым) остаётся честным правилом по умолчанию.
        return Consensus(medoid(ts), emptyMap(), sources = 1)
    }

    val outRows = ArrayList<List<String>>(slots.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()

    slots.forEachIndexed { r, slot ->
        // Строку видело каждое чтение — или не каждое. Второе не то же самое, что согласие:
        // спорить не с кем, и голосование по одному источнику всегда «единогласно».
        val seenByEveryone = slot.count { it != null } == ts.size
        // Столбцы — тоже по содержимому (#294): чтение, пропустившее узкий столбец бланка,
        // сдвинуто внутри строки, и голосование по индексу сравнивало бы соседние значения.
        val columns = columnsOf(slot.filterNotNull())
        val row = ArrayList<String>(columns.size)
        columns.forEachIndexed { c, readings ->
            // The vote itself is [agree] (#222, шаг 7) — same mechanics, no longer table-only.
            // What stays here is the table's own dressing: the ⚠ marker and the candidate cap.
            val verdict = agree(readings)
            if (verdict == null) {
                row.add(""); return@forEachIndexed
            }
            if (verdict.agreed && (seenByEveryone || verdict.value.isBlank())) {
                row.add(verdict.value) // every present read agrees
            } else {
                row.add(if (verdict.value.contains('⚠')) verdict.value else "${verdict.value}⚠")
                // Вариантов нет — выбирать не из чего: дропдаун бы предложил одно значение
                // против пустоты. Пометка здесь значит «это видело одно чтение», а не «выберите».
                if (!verdict.agreed) candidates[r to c] = verdict.candidates.filter { it.length <= 80 }
            }
        }
        outRows.add(row)
    }
    return Consensus(outRows, candidates, sources = ts.size)
}

/**
 * Чтение, ближе всех стоящее к остальным, — по совпадению значений, а не по числу строк.
 *
 * Считается свёрнутыми ячейками: у каждой пары чтений берётся доля общих значений, у каждого
 * чтения — среднее по парам. Двух чтений для такого сравнения не хватает (доля симметрична и
 * одинакова у обоих), поэтому там побеждает первое — то есть порядок провайдеров, где сильнейший
 * стоит первым.
 */
private fun medoid(tables: List<List<List<String>>>): List<List<String>> {
    if (tables.size < 3) return tables.first()
    val values = tables.map { t -> t.flatten().map(::normConsensus).filter { it.isNotEmpty() }.toSet() }
    fun share(a: Set<String>, b: Set<String>): Double {
        val union = (a + b).size
        return if (union == 0) 0.0 else a.intersect(b).size.toDouble() / union
    }
    val best = values.indices.maxByOrNull { i ->
        values.indices.filter { it != i }.map { share(values[i], values[it]) }.average()
    } ?: 0
    return tables[best]
}

/**
 * Доля строк, которые увидело **каждое** чтение. Это мера того, об одной ли странице говорят
 * источники: у двух чтений одного бланка совпадает костяк строк, у чтения и пересказа — нет.
 */
private fun agreedShare(slots: List<List<List<String>?>>, sources: Int): Double =
    if (slots.isEmpty()) 1.0 else slots.count { slot -> slot.count { it != null } == sources }.toDouble() / slots.size

/**
 * Ниже этой доли общих строк чтения считаются рассказами о разных таблицах, и смешивать их
 * нельзя. Порог — суждение, и он назван вслух: две трети означает «костяк общий, расхождения
 * по краям». Живые прогоны кадра 23 легли по обе стороны — 27 общих строк из 49 (55%, смесь
 * бессмысленна) и почти полное совпадение с тринадцатью дописанными строками (73%, смесь
 * осмысленна, а дописанное помечено).
 */
private const val MIN_ALIGNED_SHARE = 2.0 / 3.0

/**
 * Ниже этого числа строк доля общих — не мера, а случайность: на таблице из двух строк одна
 * пропущенная шапка даёт 50%, и правило выбросило бы второе чтение там, где оно спорит по делу.
 */
private const val MIN_SLOTS_TO_JUDGE_ALIGNMENT = 8

/**
 * Ячейки одной строки документа, разложенные по столбцам: `columns[c]` — что прочитал в этом
 * столбце каждый источник, в порядке источников.
 *
 * Сетку столбцов задаёт первое чтение строки, остальные раскладываются по ней [alignCells].
 * Ячейка, которой места в сетке не нашлось, встаёт **новым столбцом справа**: выбросить её
 * значило бы потерять прочитанное молча, а вдвинуть в середину — сдвинуть эту строку
 * относительно всех прочих строк файла.
 */
private fun columnsOf(readings: List<List<String>>): List<List<String>> {
    val base = readings.firstOrNull().orEmpty()
    val columns = base.mapTo(mutableListOf()) { mutableListOf(it) }
    readings.drop(1).forEach { row ->
        val places = alignCells(base, row)
        row.forEachIndexed { j, value ->
            val c = if (places == null) j else places[j]
            if (c == null) {
                if (value.isNotBlank()) columns += mutableListOf(value)
            } else {
                while (columns.size <= c) columns += mutableListOf<String>()
                columns[c] += value
            }
        }
    }
    return columns
}

/**
 * Куда ложится каждая ячейка чтения [row] в столбцах опорной строки [base]; `null` в позиции —
 * места в сетке нет. `null` вместо всей раскладки — содержимое не даёт оснований двигать
 * ячейки, и столбцы считаются по индексу, как раньше.
 *
 * Живой случай (#294, ведомость владельца): одна модель отдала строку `… 1,000 | 0,087 | 1,375 |
 * 0,120 | 0,625 | 0,054`, другая пропустила узкий столбец бланка и дописала хвост —
 * `… 1,000 | 1,375 | 0,125 | 0,625 | 0,875 | 0,875`. По индексу спорной становилась **каждая**
 * ячейка после пропуска, и в дропдауне рядом с «0,087» стояло «1,375» — не другое чтение, а
 * значение соседнего столбца. По содержимому совпавшие значения встречаются, пропуск читается
 * как отсутствие, а спорят ровно те ячейки, где модели правда разошлись («0,120» против «0,125»).
 *
 * Раскладка принимается, только если она **строго** лучше индексной: сдвиг надо доказать
 * совпадениями, иначе таблица, где модели разошлись во всех ячейках, «выравнивалась» бы в
 * произвольную перестановку. Настоящий спор при этом уцелеет — на нём раскладка не выигрывает.
 */
private fun alignCells(base: List<String>, row: List<String>): List<Int?>? {
    fun same(a: String?, b: String?): Boolean {
        val x = a?.let(::normConsensus).orEmpty()
        return x.isNotEmpty() && x == b?.let(::normConsensus)
    }
    val byIndex = row.indices.count { same(base.getOrNull(it), row[it]) }
    // Соседние односторонние ячейки — замена (одно место документа, прочитанное по-разному),
    // иначе спор о значении растворился бы в «пропуск + находка»; правдоподобие тут ни при чём —
    // это ячейки одной и той же строки.
    val matched = lcsOps(base.size, row.size) { i, j -> same(base[i], row[j]) }
    val ops = pairSubstitutions(matched) { _, _ -> true }
    val byContent = ops.count { (i, j) -> i != null && j != null && same(base[i], row[j]) }
    if (byContent <= byIndex) return null
    val places = arrayOfNulls<Int>(row.size)
    ops.forEach { (i, j) -> if (j != null) places[j] = i }
    return places.toList()
}

/**
 * Строки всех чтений, разложенные по слотам общей сетки (#294).
 *
 * Слот — одна строка документа: `slot[i]` — как её прочитала таблица `i`, либо `null`, если
 * это чтение строки не увидело. Пропущенная строка честно голосуется как **отсутствие**
 * ([agree] «ничего не прочитано ≠ спор»), а не как чужая строка.
 *
 * Выравнивание — наибольшая общая подпоследовательность по «похожести строк»
 * ([rowsSimilar]): порядок строк документа сохраняется всеми чтениями, поэтому перестановки
 * не ищутся — только пропуски и вставки. Первое чтение задаёт сетку, каждое следующее
 * пристраивается к ней, а его собственные находки становятся новыми слотами на своём месте.
 */
internal fun alignRows(tables: List<List<List<String>>>): List<List<List<String>?>> {
    var slots: MutableList<MutableList<List<String>?>> =
        tables.first().mapTo(mutableListOf()) { mutableListOf<List<String>?>(it) }
    for (t in 1 until tables.size) {
        val rows = tables[t]
        val grid = slots.map { slot -> slot.firstOrNull { it != null }!! }
        val next = mutableListOf<MutableList<List<String>?>>()
        // Ключ строки — если он есть — надёжнее похожести: рукописные пометки поверх бланка
        // меняют половину ячеек, но артикул остаётся артикулом.
        val key = keyColumns(grid, rows)
        val pairs = if (key != null) matchByKey(grid, rows, key) else matchRows(grid, rows)
        for ((slotIdx, rowIdx) in pairs) {
            when {
                slotIdx != null && rowIdx != null -> next += slots[slotIdx].also { it += rows[rowIdx] }
                slotIdx != null -> next += slots[slotIdx].also { it += null }
                // Строка, которой в сетке ещё не было: у прежних чтений её просто нет.
                rowIdx != null -> next += MutableList<List<String>?>(t) { null }.also { it += rows[rowIdx] }
            }
        }
        slots = next
    }
    return slots
}

/**
 * Ключевая колонка — столбец, по которому строки таблицы узнают друг друга (#294, эталонная
 * ведомость владельца).
 *
 * На реальной ведомости первая колонка — артикул (`11004`, `11006`, `11012`…), и он опознаёт
 * строку надёжнее, чем похожесть всей строки: рукописные пометки поверх бланка меняют половину
 * ячеек, строки перестают быть «похожими» и разъезжаются по разным слотам — в файле появляется
 * строка-фантом, а значения уезжают к соседям.
 *
 * Колонка считается ключевой, только если она **действительно ключ**: значения в ней уникальны
 * внутри каждого чтения и хотя бы половина из них встречается в обоих. Не нашли такую — работаем
 * по прежнему пути (похожесть строк), а не выдумываем ключ.
 */
private fun keyColumns(a: List<List<String>>, b: List<List<String>>): Pair<Int, Int>? {
    fun keys(t: List<List<String>>, c: Int) =
        t.mapNotNull { it.getOrNull(c)?.let(::normConsensus)?.takeIf { v -> v.isNotEmpty() } }
    val wa = a.maxOfOrNull { it.size } ?: 0
    val wb = b.maxOfOrNull { it.size } ?: 0
    var best: Pair<Int, Int>? = null
    var bestShared = 0
    // Пары столбцов, а не один и тот же индекс: на живой ведомости одно чтение отдало артикул
    // первой колонкой, другое — второй (первую заняла пустая колонка бланка), и ключ, искомый
    // по совпадающему индексу, не находился вовсе.
    for (ca in 0 until minOf(wa, MAX_KEY_SCAN)) {
        val ka = keys(a, ca)
        if (ka.size < MIN_KEYED_ROWS || ka.toSet().size != ka.size) continue
        for (cb in 0 until minOf(wb, MAX_KEY_SCAN)) {
            val kb = keys(b, cb)
            if (kb.size < MIN_KEYED_ROWS || kb.toSet().size != kb.size) continue
            // Почти все, а не половина: на «Итого» против «Всего» половина совпадений находится
            // случайно, и подписи строк выдали бы себя за идентификаторы (поймано тестом #294).
            val shared = ka.count { it in kb.toSet() }
            if (shared * 5 >= minOf(ka.size, kb.size) * 4 && shared > bestShared) {
                best = ca to cb
                bestShared = shared
            }
        }
    }
    return best
}

/** Дальше третьей колонки идентификатор строки не прячется, а перебор пар дорожает квадратично. */
private const val MAX_KEY_SCAN = 3

/** Ниже этого числа опознанных строк «ключ» — совпадение, а не свойство таблицы. */
private const val MIN_KEYED_ROWS = 5

/**
 * Пары «слот сетки ↔ строка чтения» по ключевой колонке: строки с одинаковым ключом — одна
 * строка документа, чем бы ни отличались остальные ячейки. Строки без ключа и с ключом, которого
 * нет у другой стороны, остаются на своих местах в порядке документа.
 */
private fun matchByKey(
    grid: List<List<String>>,
    rows: List<List<String>>,
    columns: Pair<Int, Int>,
): List<Pair<Int?, Int?>> {
    fun key(row: List<String>, c: Int) = row.getOrNull(c)?.let(::normConsensus)?.takeIf { it.isNotEmpty() }
    fun key(row: List<String>) = key(row, columns.first)
    val rowByKey = rows.indices.mapNotNull { i -> key(rows[i], columns.second)?.let { it to i } }.toMap()
    val used = mutableSetOf<Int>()
    val out = mutableListOf<Pair<Int?, Int?>>()
    grid.indices.forEach { g ->
        val match = key(grid[g])?.let(rowByKey::get)
        if (match != null && used.add(match)) out += g to match else out += g to null
    }
    // Строки чтения, не нашедшие своего ключа в сетке, — его собственные находки: они встают
    // после ближайшей уже сопоставленной строки, чтобы не всплыть в конце документа.
    rows.indices.filter { it !in used }.forEach { r ->
        val after = out.indexOfLast { it.second != null && it.second!! < r }
        if (after >= 0) out.add(after + 1, null to r) else out.add(0, null to r)
    }
    return out
}

/**
 * Пары «слот сетки ↔ строка чтения» в порядке документа: `null` с одной стороны — пропуск.
 * Классический LCS: длина совпадений максимизируется, порядок сохраняется.
 */
private fun matchRows(
    grid: List<List<String>>,
    rows: List<List<String>>,
): List<Pair<Int?, Int?>> =
    pairSubstitutions(lcsOps(grid.size, rows.size) { i, j -> rowsSimilar(grid[i], rows[j]) }) { i, j ->
        sameRowPossible(grid[i], rows[j])
    }

/**
 * Классический LCS-обход двух последовательностей: пары «индекс слева ↔ индекс справа», `null`
 * с одной стороны — пропуск. Длина совпадений максимизируется, порядок сохраняется.
 *
 * Общий для строк и для ячеек внутри строки: обе задачи — «те же элементы, кто-то что-то
 * пропустил», и две копии одного обхода разошлись бы на первой правке.
 */
private fun lcsOps(n: Int, m: Int, similar: (Int, Int) -> Boolean): List<Pair<Int?, Int?>> {
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (similar(i, j)) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val raw = mutableListOf<Pair<Int?, Int?>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            similar(i, j) -> { raw += i to j; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { raw += i to null; i++ }
            else -> { raw += null to j; j++ }
        }
    }
    while (i < n) raw += i++ to null
    while (j < m) raw += null to j++
    return raw
}

/**
 * Могут ли это быть два чтения ОДНОЙ строки документа (#294, эталонная ведомость владельца).
 *
 * Идентификатор строки спором не бывает: `11401` и `11029` — две разные строки бланка, а не два
 * чтения одной, как бы близко они ни оказались в ответах моделей. Без этой проверки замена
 * ([pairSubstitutions]) склеивала в один слот что попало — на живом прогоне строку данных с
 * **шапкой** второго чтения, и человек получал дропдаун, где «0,831» предлагалось заменить на
 * «Рота зв'язку». Три такие склейки дали в файле почти все дропдауны, и ни один из них не был
 * спором о значении.
 *
 * Идентификатор — целое из 3–6 цифр ([ID_SHAPED], та же форма, что у [validateTable]): «1,917»
 * им не считается (свёртка числа оставляет точку), поэтому строки с расходящимися количествами
 * по-прежнему считаются одной строкой и голосуются. Пустая ячейка ничего не опровергает —
 * отсутствие не разногласие.
 */
private fun sameRowPossible(a: List<String>, b: List<String>): Boolean {
    for (c in 0 until minOf(a.size, b.size)) {
        val x = normConsensus(a[c])
        val y = normConsensus(b[c])
        if (x.isEmpty() || y.isEmpty()) continue
        if (!ID_SHAPED.matches(x) && !ID_SHAPED.matches(y)) continue
        if (x != y) return false
    }
    return true
}

/** Артикул/номер строки: целое из 3–6 цифр — та же форма, которой [validateTable] узнаёт ряд id. */
private val ID_SHAPED = Regex("""\d{3,6}""")

/**
 * Соседние «только сетка» и «только чтение» — это ЗАМЕНА одной строки, а не потеря и находка
 * (ревью #294). Разорванная надвое, такая строка попадала в два слота и голосовалась в
 * одиночку — то есть консенсус выключался ровно там, где модели разошлись сильнее всего:
 * спор исчезал, ⚠ не ставился, а в файле появлялась лишняя строка.
 *
 * Классический diff называет это substitution; здесь она и восстанавливается, чтобы обе
 * версии строки встретились в одном слоте и рассудились [agree].
 *
 * [plausible] — право вето: не всякая пара соседей одна и та же строка документа (см.
 * [sameRowPossible]). Склеенные без разбора, они рождали спор из ничего — и именно такие
 * дропдауны человек видел на эталонной ведомости.
 */
private fun pairSubstitutions(
    ops: List<Pair<Int?, Int?>>,
    plausible: (Int, Int) -> Boolean,
): List<Pair<Int?, Int?>> {
    val out = mutableListOf<Pair<Int?, Int?>>()
    var k = 0
    while (k < ops.size) {
        val cur = ops[k]
        val next = ops.getOrNull(k + 1)
        val curIsGridOnly = cur.first != null && cur.second == null
        val curIsRowOnly = cur.first == null && cur.second != null
        val nextIsGridOnly = next != null && next.first != null && next.second == null
        val nextIsRowOnly = next != null && next.first == null && next.second != null
        when {
            curIsGridOnly && nextIsRowOnly && plausible(cur.first!!, next!!.second!!) ->
                { out += cur.first to next.second; k += 2 }
            curIsRowOnly && nextIsGridOnly && plausible(next!!.first!!, cur.second!!) ->
                { out += next.first to cur.second; k += 2 }
            else -> { out += cur; k++ }
        }
    }
    return out
}

/**
 * Одна ли это строка документа: большинство сопоставимых ячеек читаются одинаково после
 * свёртки формата ([normConsensus]). Сравниваются только позиции, где обе стороны непусты —
 * иначе короткое чтение строки не совпало бы с полным ни с одной.
 */
private fun rowsSimilar(a: List<String>, b: List<String>): Boolean {
    var comparable = 0
    var same = 0
    for (c in 0 until minOf(a.size, b.size)) {
        val x = normConsensus(a[c])
        val y = normConsensus(b[c])
        if (x.isEmpty() || y.isEmpty()) continue
        comparable++
        if (x == y) same++
    }
    // Строгое большинство, а не половина (ревью #294): в узкой таблице с повторяющимся
    // столбцом «1|5» и «2|5» совпадали ровно наполовину и склеивались в одну строку —
    // разные строки документа теряли друг друга. Половину теперь разбирает pairSubstitutions:
    // соседние односторонние слоты — это замена, и обе версии встречаются в одном слоте.
    return comparable > 0 && same * 2 > comparable
}
