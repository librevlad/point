package com.point.core.flow

/**
 * Улики офлайн-правил на атомах слоя (#258, design v3 §4): id атома → имена совпавших форм.
 *
 * Правило **размечает токен, не решая его роль** — разрешение живого спора консилиума: словари
 * и геометрия до модели дают ложные якоря («Кому» внутри цитаты), а роль, зафиксированная до
 * контекста, невосстановима. Поэтому улика попадает в разметку входа модели (`rule=track-shaped`
 * в [promptIndex]) и никогда — в отсев или в готовое значение. Одно правило — предположение,
 * и оно видно как предположение.
 *
 * Правила двух видов:
 * - **пробеги**: форма, собранная из соседних слов, — 14-значный трек приходит тремя атомами
 *   (`20` + `4514 9154` + `9395`), и судить её можно только по группе; помечается каждый атом
 *   группы;
 * - **токены**: форма целого слова — `14:32` похоже на время суток, а не на дату документа
 *   (живой случай #244, где время статуса вытесняло настоящую дату).
 *
 * Пробег судится **геометрией, а не склейкой строки** (ревью #283, две находки одного корня).
 * Первая версия гнала регекс по всей геометрической строке и на строках таблиц систематически
 * лгала в обе стороны: четыре независимые ячейки «2500 4000 100 500» (в сумме ровно 14 цифр)
 * помечались одним «треком», а настоящий трек с цифровым соседом («1 20 4514 9154 9395»)
 * терялся молча — жадный матч втягивал соседа, проваливал счётчик и пропускал всё. Поэтому:
 *
 * - строка режется на пробеги по **зазору колонок** ([CELL_GAP_HEIGHTS]): пробел внутри числа —
 *   доли высоты строки, зазор ячеек таблицы — больше высоты; геометрию атомы принесли с собой,
 *   выбрасывать её и судить голый текст — тот же грех, что судить строку OCR вместо страницы;
 * - внутри пробега форма судится **окнами по границам атомов**: цепочки чисто цифровых атомов,
 *   левые непересекающиеся окна с суммой цифр ровно [WAYBILL_DIGITS] — это семантика
 *   `findAll(WAYBILL_SHAPED)` + фильтр счётчика, ограниченная границами слов страницы, поэтому
 *   дата «15.06.2025» (точки — не цифры) в пробег не въезжает, а сосед после трека — не хвост
 *   матча.
 *
 * Остаточный класс ложных улик задокументирован: группы цифр, стоящие вплотную (зазор меньше
 * высоты) и дающие в сумме ровно 14, — по геометрии и форме от трека неотличимы; улика мягкая,
 * промпт говорит модели, что она может ошибаться.
 */
fun AtomLayer.ruleEvidence(): Map<String, List<String>> {
    val evidence = LinkedHashMap<String, MutableList<String>>()
    fun mark(atom: Atom, rule: String) {
        val rules = evidence.getOrPut(atom.id) { mutableListOf() }
        if (rule !in rules) rules += rule
    }
    lines(atoms.filter { it.text.isNotBlank() }).forEach { line ->
        line.forEach { atom ->
            if (BARE_CLOCK.matches(atom.text.trim())) mark(atom, "clock-shaped")
        }
        cellRuns(line).forEach { run ->
            digitStretches(run).forEach { stretch ->
                trackWindows(stretch).forEach { window ->
                    window.forEach { mark(it, "track-shaped") }
                }
            }
        }
    }
    return evidence
}

/**
 * Строка, разрезанная по зазорам колонок: сосед дальше [CELL_GAP_HEIGHTS] высот — другая ячейка.
 * Порог в высотах атома, не в пикселях — страница приходит в любом разрешении (тот же принцип,
 * что полоса строки и радиус связности).
 */
private fun cellRuns(line: List<Atom>): List<List<Atom>> {
    val runs = mutableListOf<MutableList<Atom>>()
    line.forEach { atom ->
        val prev = runs.lastOrNull()?.last()
        val split = prev != null &&
            atom.box.left - prev.box.right > maxOf(prev.box.height, atom.box.height) * CELL_GAP_HEIGHTS
        if (prev == null || split) runs += mutableListOf(atom) else runs.last() += atom
    }
    return runs
}

/** Максимальные цепочки подряд идущих чисто цифровых атомов пробега. */
private fun digitStretches(run: List<Atom>): List<List<Atom>> {
    val stretches = mutableListOf<MutableList<Atom>>()
    var open = false
    run.forEach { atom ->
        if (atom.isDigitRun()) {
            if (!open) stretches += mutableListOf<Atom>().also { open = true }
            stretches.last() += atom
        } else {
            open = false
        }
    }
    return stretches
}

/** Цифры одного числа, как их пишет человек: цифры, внутри могут стоять пробелы. */
private fun Atom.isDigitRun(): Boolean =
    text.trim().let { t -> t.isNotEmpty() && t.all { it.isDigit() || it == ' ' } }

/**
 * Левые непересекающиеся окна подряд идущих атомов с суммой цифр ровно [WAYBILL_DIGITS] —
 * зеркало `findAll`: нашли — помечаем и продолжаем после окна, не нашли — сдвигаем начало.
 * Сумма по окну строго растёт, поэтому у каждого начала не больше одной точной границы.
 */
private fun trackWindows(stretch: List<Atom>): List<List<Atom>> {
    val windows = mutableListOf<List<Atom>>()
    var i = 0
    while (i < stretch.size) {
        var sum = 0
        var j = i
        while (j < stretch.size && sum < WAYBILL_DIGITS) {
            sum += stretch[j].text.count(Char::isDigit)
            j++
        }
        if (sum == WAYBILL_DIGITS) {
            windows += stretch.subList(i, j)
            i = j
        } else {
            i++
        }
    }
    return windows
}

/** Зазор ячеек таблицы против пробела внутри числа: пробел — доли высоты строки, колонка — больше. */
private const val CELL_GAP_HEIGHTS = 1f
