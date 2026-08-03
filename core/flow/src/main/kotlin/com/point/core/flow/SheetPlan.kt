package com.point.core.flow

/**
 * Документ, уложенный **на один лист** (#266).
 *
 * Писатель получает **факты стиля, а не роли документа**: он не должен знать, что такое «итог»
 * или «подписной блок», — иначе оформление начнёт зависеть от того, как модель назвала область,
 * и роль из свидетельства превратится в переключатель.
 *
 * Лист один сознательно. Книга из листов «Шапка / Таблица / Подписи» выглядит как полнота, но на
 * деле придумывает документу информационную архитектуру, которой в нём нет, и заводит навигацию:
 * человек открывает файл и выбирает, на каком листе ответ.
 */
data class SheetPlan(
    val rows: List<List<String>>,
    /**
     * Строки листа, которые действительно являются заголовками сетки, — **по факту, а не по
     * позиции**. У документа без строки заголовков (счёт, где сетка начинается сразу под
     * подписью) первая строка данных красилась «шапкой», и это была жирная ложь.
     */
    val headerRows: Set<Int>,
    /** Спорные ячейки листа → чтения для дропдауна, в координатах ЛИСТА. */
    val candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
)
/*
 * Полей ровно столько, сколько сегодня есть кому произвести и кому прочитать. Числовые форматы,
 * причина пометки в подсказке ячейки и стиль строки-реквизита приходят следующим срезом — вместе
 * со своими потребителями в писателе. Контракт без потребителя мёртв ровно так же, как поле
 * `StyledCell.original`, у которого в продакшн-коде до сих пор ноль читателей.
 */

/**
 * Тривиальный план — сегодняшнее поведение дословно: строки как есть, шапка по позиции.
 *
 * Существует ради совместимости: писатель, которому плана не дали, обязан вести себя ровно так
 * же, как вёл до появления блоков.
 */
fun sheetPlanOf(
    rows: List<List<String>>,
    candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
): SheetPlan = SheetPlan(rows, if (rows.isEmpty()) emptySet() else setOf(0), candidates)

/**
 * Подпись над словами, которые на странице есть, но ни к чему не отнесены.
 *
 * Строка нужна затем же, зачем строка происхождения в документе с рукописи: без неё эти слова
 * выглядят как обычные данные таблицы, и человек либо поверит им как ячейкам, либо решит, что
 * файл собран криво. Названо продуктовым языком — человек читает Excel, а не наш журнал.
 */
const val UNREAD_CAPTION = "Непрочитанное — эти слова есть на странице, но не попали ни в одну часть документа"

/**
 * Раскладка документа на лист: блоки идут в порядке документа, сетка — своими строками, всё
 * остальное — строкой на блок.
 *
 * [BlockRole.CHROME] на лист **не едет**: модель сказала, что это не документ (строка состояния,
 * панель приложения, соседнее окно), и переносить её в таблицу значило бы заполнить файл шумом,
 * ради борьбы с которым корзина и заведена. Молчанием это не является — область названа, а
 * потерянным считается только то, чего не назвал никто ([DocumentLayout.uncovered]).
 *
 * [mode] решает единственный вопрос — что в этом файле нельзя отдавать как прочитанное
 * ([uncertainInExport]). На рукописи цифры помечаются всегда: правило «модель не трогает цифры»
 * там структурно неисполнимо, и честная пометка остаётся единственной заменой гарантии.
 */
fun layoutSheet(layout: DocumentLayout, mode: ReadingMode = ReadingMode.UNKNOWN): SheetPlan {
    val rows = ArrayList<List<String>>()
    val headerRows = LinkedHashSet<Int>()
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    var captioned = false
    for (block in layout.blocks) {
        if (block.role == BlockRole.CHROME) continue
        if (block.role == BlockRole.UNREAD && !captioned) {
            captioned = true
            rows += listOf(UNREAD_CAPTION)
        }
        val grid = block.grid
        if (grid != null) {
            val from = rows.size
            grid.rows.forEach { row -> rows += row.map { marked(it, mode) } }
            repeat(minOf(block.headerRows, grid.rows.size)) { headerRows += from + it }
            grid.candidates.forEach { (cell, readings) ->
                candidates[(from + cell.first) to cell.second] = readings
            }
        } else {
            val line = buildList {
                if (block.label.isNotEmpty()) add(marked(block.label, mode))
                if (block.text.isNotEmpty() || isEmpty()) add(marked(block.text, mode))
            }
            // Пустой блок без единой пометки не несёт ничего — строка из одной пустой ячейки
            // была бы дырой в документе, а не его частью.
            if (line.any { it.isNotEmpty() }) rows += line
        }
    }
    return SheetPlan(rows, headerRows, candidates)
}

/**
 * Можно ли сказать человеку «ничего не потеряно» — и `null`, когда судить не по чему.
 *
 * Обещание разное по режиму чтения, и цена названа вслух:
 * - [ReadingMode.HANDWRITTEN] — атомов нет, покрытие недостижимо по построению, поэтому обещание
 *   слабее: не «всё на месте», а **«ничто не притворяется прочитанным»** — ни одна цифра не ушла
 *   в файл без пометки;
 * - иначе — каждое прочитанное движком слово присвоено: ячейке, блоку вокруг, хрому или
 *   «непрочитанному» ([DocumentLayout.uncovered] пуст);
 * - `null` — покрытие не измерялось ([DocumentLayout.coverage] пусто): модель не указала ни на
 *   одно слово страницы, и утверждать что-либо в любую сторону было бы выдумкой.
 */
fun coveredClaim(layout: DocumentLayout, plan: SheetPlan, mode: ReadingMode): Boolean? = when {
    mode == ReadingMode.HANDWRITTEN ->
        plan.rows.asSequence().flatten().none { it.any(Char::isDigit) && !marks(it) }
    layout.coverage == null -> null
    else -> layout.uncovered.isEmpty()
}

/**
 * Пометка ячейки на вычитку. Уже помеченное **не переписывается**: у исправления («~~53~~ 40»)
 * есть свой видимый знак и своя заливка, и заменить её на оранжевую значило бы стереть то, что
 * она сообщала — что версий две и выбирает человек.
 */
private fun marked(text: String, mode: ReadingMode): String =
    if (text.isNotEmpty() && uncertainInExport(text, mode) && !marks(text)) "$text⚠" else text

private fun marks(text: String): Boolean = text.contains('⚠') || text.contains(STRIKE_FENCE)
