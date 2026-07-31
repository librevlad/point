package com.point.core.flow

/**
 * Ответ модели про одну ячейку таблицы (#258).
 *
 * На печатном документе модель **указывает, а не диктует**: ячейка — это метки слов, уже
 * прочитанных с страницы ([Ids]), и её текст собирается из атомов, чей текст модель изменить
 * не может. Дословный текст ([Literal]) остаётся для того, чего в слое нет: рукописная пометка,
 * слово, которое движок не увидел, или модель, отвечающая по старому контракту.
 */
sealed interface CellAnswer {
    /**
     * Метки атомов, из которых состоит ячейка. [text] — собственное чтение модели, и оно
     * **не значение**: совпало с атомами — забыто, починило буквы — принято как ремонт
     * ([isRepairOf]), тронуло цифру — уходит в кандидаты рядом с атомным чтением.
     */
    data class Ids(val ids: List<String>, val text: String? = null) : CellAnswer

    /** Дословный текст модели — происхождение «прочитано моделью», а не «прочитано со страницы». */
    data class Literal(val text: String) : CellAnswer
}

/**
 * Таблица, собранная из ответа модели поверх слоя атомов: [rows] — тексты ячеек (спорные — с ⚠),
 * [candidates] — чтения, между которыми ячейка спорит, в формате [Consensus.candidates], чтобы
 * писатель показал их тем же дропдауном.
 */
data class GroundedTable(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>>,
)

/**
 * Собирает таблицу из ответа модели, читая ячейки-[CellAnswer.Ids] **из атомов** через
 * [resolve] — единственный путь, которым модельный ответ становится текстом на печатном
 * документе. Порванная связь ответа с координатами не глотается:
 *
 * - галлюцинированные метки отброшены резолвером → ячейка помечена ⚠ — даже пустая: «модель
 *   указала в никуда» и «честно не разобрано» — разные вещи, и склеивать их значком нельзя
 *   (ревью #281 — сигнал разрыва уничтожался гвардом на пустоту);
 * - пространственно несвязный набор → ⚠ (значение собрано, но это предположение);
 * - все метки чужие, но модель дала своё чтение → чтение остаётся с ⚠: связь со страницей
 *   не подтверждена, а молча выбросить прочитанное — тот же грех, что молча поверить.
 *
 * Чтение модели против атомов судится готовыми правилами консенсуса: формальный шум складывает
 * [normConsensus], ремонт букв пропускает [isRepairOf] (цифры неприкосновенны), настоящий спор
 * виден как ⚠ + оба чтения в кандидатах.
 *
 * Дословная ячейка ([CellAnswer.Literal]) — законный путь для того, чего в слое нет. Но слой
 * здесь **есть** (иначе эту функцию некому звать), и дословная **цифра**, которой нет нигде в
 * прочитанном, — это диктовка мимо страницы: ровно та подмена, от которой контракт защищает.
 * Она не выбрасывается (модель могла прочитать то, что движок пропустил), но помечается ⚠
 * (ревью #281 — просьба в промпте «текст только когда слов нет в списке» кодом не подкреплялась).
 */
fun AtomLayer.resolveCells(cells: List<List<CellAnswer>>): GroundedTable {
    val page = pageValues(text)
    val rows = ArrayList<List<String>>(cells.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    cells.forEachIndexed { r, row ->
        rows += row.mapIndexed { c, cell ->
            var flagged = false
            val text = when (cell) {
                is CellAnswer.Literal -> {
                    val folded = pageFold(cell.text)
                    flagged = cell.text.any { it.isDigit() } && folded.isNotEmpty() && folded !in page
                    cell.text
                }
                is CellAnswer.Ids -> {
                    val v = resolve(AtomAddress.ByIds(cell.ids))
                    // Маркеры неуверенности и правок — не текст: в ids-ячейке модельное чтение
                    // сравнивается с атомами, и «Гречка⚠» против «Гречка» — не спор.
                    val model = cell.text?.replace("⚠", "")?.replace("~~", "")?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    flagged = v.droppedIds.isNotEmpty() || v.disjoint
                    when {
                        v.atoms.isEmpty() -> {
                            if (model != null) flagged = true
                            model ?: ""
                        }
                        model == null || normConsensus(model) == normConsensus(v.text) -> v.text
                        isRepairOf(v.text, model) -> model
                        else -> {
                            flagged = true
                            // Дропдаун из одного варианта — не выбор: длинные чтения срезаны
                            // фильтром писателя, и пустой/одиночный список туда не идёт (⚠ остаётся).
                            listOf(v.text, model).distinct().filter { it.length <= 80 }
                                .takeIf { it.size >= 2 }
                                ?.let { candidates[r to c] = it }
                            v.text
                        }
                    }
                }
            }
            if (flagged && !text.contains('⚠')) "$text⚠" else text
        }
    }
    return GroundedTable(rows, candidates)
}

/**
 * Слой как индекс слов для запроса модели: строки страницы, каждое слово с меткой —
 * `[w12]Дата [w13]Сумма`. Модель отвечает метками, текст собирает [resolveCells].
 *
 * Улики офлайн-правил ([ruleEvidence]) едут атрибутом метки — `[w7 rule=track-shaped]9395`:
 * подсказка о форме, видимая модели ровно там, где ей отвечать, и ничего не решающая
 * (design v3 §4 — правила размечают вход, а не роль).
 *
 * `null` — индекса не будет, честно и целиком:
 * - слой пуст или прочитанное — символьная каша: рукопись и фото мира дают бессмысленные
 *   атомы, и индекс из них только собьёт модель с собственных глаз;
 * - слов больше [MAX_PROMPT_ATOMS]: обрезанный индекс — это молчаливо потерянные слова,
 *   ровно та тихая ложь, от которой слой лечит; лучше старый контракт без индекса.
 */
fun AtomLayer.promptIndex(): String? {
    val named = atoms.filter { it.text.isNotBlank() }
    if (named.isEmpty() || named.size > MAX_PROMPT_ATOMS) return null
    if (symbolSoup(text)) return null
    val evidence = ruleEvidence()
    return lines(named).joinToString("\n") { line ->
        line.joinToString(" ") { atom ->
            val rules = evidence[atom.id]
            val attr = if (rules.isNullOrEmpty()) "" else " rule=" + rules.joinToString(",")
            "[${atom.id}$attr]${atom.text}"
        }
    }
}

/**
 * Каша ли прочитанное — для индекса, не для показа. [looksLikeOcrGarbage] здесь не годится:
 * он бракует «мало букв» и «мало длинных слов», а чек и посылочный экран — это цифры при паре
 * слов, и это ровно те документы, которые привязка к странице бережёт (цифры несут identity —
 * [isRepairOf] их и не даёт трогать). Каша — когда символам не из букв и цифр принадлежит
 * почти всё: такие атомы нечего цитировать.
 */
private fun symbolSoup(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace == 0) return true
    val readable = text.count { it.isLetterOrDigit() }
    return readable.toDouble() / nonSpace < 0.6
}

/**
 * Значения, которые страница может подтвердить: свёртки **цепочек целых подряд идущих слов**
 * каждой строки. Именно целых слов, а не подстрока склейки всей страницы: в склейке без пробелов
 * «1491» находится как хвост «4514» + голова «9154», хотя такого числа на странице нет, — число,
 * собранное не из тех кусков и тихо отданное как валидное, это ровно болезнь design v3 §2,
 * от которой гейт диктовки и защищает (ревью #258 — гейт пробивался сплайсом через стёртые
 * границы слов и строк).
 */
private fun pageValues(text: String): Set<String> {
    val values = HashSet<String>()
    text.split('\n').forEach { line ->
        val tokens = line.split(WHITESPACE).map(::pageFold).filter { it.isNotEmpty() }
        tokens.indices.forEach { i ->
            val chain = StringBuilder()
            var j = i
            while (j < tokens.size && chain.length + tokens[j].length <= MAX_VALUE_LEN) {
                chain.append(tokens[j])
                values.add(chain.toString())
                j++
            }
        }
    }
    return values
}

/** Свёртка для сверки со страницей: формат-шум [normConsensus] плюс `/` и `:` — «16:00» на
 *  странице подтверждает продиктованное «1600», иначе честная цифра ловит ⚠ за наш же формат. */
private fun pageFold(s: String): String = normConsensus(s).replace("/", "").replace(":", "")

private val WHITESPACE = Regex("""\s+""")

/** Длиннее этого значения на странице не живут (IBAN — 29 знаков): потолок держит набор цепочек
 *  малым даже на плотной странице в [MAX_PROMPT_ATOMS] слов. */
private const val MAX_VALUE_LEN = 64

/** Потолок индекса: плотная страница — сотни слов, разворот книги — уже нет. */
const val MAX_PROMPT_ATOMS = 600
