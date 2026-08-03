package com.point.core.flow

/**
 * Документ читается **набором адресованных областей**, а не одной сеткой (#266).
 *
 * Сегодня шапка, реквизиты, примечание и подписной блок теряются молча: у ответа модели есть
 * ровно одна допустимая форма — «массив строк из строк-ячеек», — и остальному документу просто
 * некуда лечь. Для ведомости это половина документа, и приёмка среза сформулирована дословно:
 *
 * > **Ничего видимого на странице не ушло в файл молча.**
 *
 * Новой подсистемы «понимание раскладки» здесь нет и не появляется. Блок — это `набор атомов +
 * роль`, разрешаемый **тем же резолвером**, что и ячейка ([resolveCells]): просто адресованных
 * областей стало больше, ровно как выделение области не стало новой подсистемой.
 */

/**
 * Роль блока — **свидетельство, а не переключатель пайплайна** (design v3 §4). Её называет
 * модель, видевшая страницу; код её не переопределяет и по ней не роутит. Роль влияет ровно на
 * две вещи: куда блок ляжет в файле и кто вправе заявить претензию на итог.
 *
 * Ошиблась в роли — блок встал не туда в файле; значения это не меняет, страница по-прежнему
 * выигрывает символы.
 */
enum class BlockRole {
    /** «Технологічна карта №…», «Сводная таблица к счету № …». */
    TITLE,

    /** Реквизит: название → значение («Клиент: Терминал Пр. 117»). */
    FIELD,

    /** Сетка. */
    TABLE,

    /** Блок итогов — строками или колонками рядом с шапкой. */
    TOTALS,

    /** Примечание, сноска, дисклеймер («кладовщик не имеет права отпускать товар»). */
    NOTE,

    /** Подписной блок. */
    SIGN,

    /**
     * Видно, но это **не документ**: строка состояния, панель приложения, соседняя карточка доски.
     *
     * Существует не как корзина для мусора, а потому что на четырёх табличных кадрах корпуса из
     * семи хром занимает заметную долю страницы (диалог и полосы прокрутки, тулбар, соседние
     * карточки, чужой документ на заднем плане). Без явной корзины «не документ» полное покрытие
     * страницы было бы недостижимо честным путём — и модель добирала бы его выдумкой.
     */
    CHROME,

    /** Модель видит слова, но не берётся их присвоить. Честный ответ, а не отказ. */
    UNREAD,
}

/**
 * Ответ модели про содержимое блока: **тот же адресный контракт, что у ячейки** ([CellAnswer]).
 * У блока нет собственного пути чтения — текст собирается из атомов ровно как текст ячейки,
 * поэтому диктовка мимо страницы получает ⚠ по той же ветке, что в [resolveCells].
 */
sealed interface BlockContent {
    data class Text(val cell: CellAnswer) : BlockContent

    /**
     * Сетка. [headerRows] — сколько СТРОК сетки заняты заголовками: `0` — шапки нет физически
     * (счёт без заголовков), `2` — двухуровневая. Сегодня «первая строка — заголовки, если они
     * есть» вообще не имеет способа сказать «их нет», и молчание разрешается в пользу «есть».
     */
    data class Grid(val cells: List<List<CellAnswer>>, val headerRows: Int) : BlockContent
}

/** Один блок в ответе модели. [label] — название реквизита; `null` — названия на странице нет. */
data class BlockAnswer(
    val role: BlockRole,
    val label: CellAnswer? = null,
    val content: BlockContent,
)

/** Заявленный охват — факт, а не догадка: за кадром бывает и лист, и прокрутка. */
enum class DocScope {
    /** Документ попал в кадр целиком. */
    FULL,

    /** Виден только экран: за краем есть ещё документ, до него доскроллили бы. */
    VIEWPORT,

    /** Часть документа обрезана краем кадра. */
    CROPPED,
}

/** Разобранный ответ модели про весь документ — вход [resolveLayout]. */
data class LayoutAnswer(val blocks: List<BlockAnswer>, val scope: DocScope? = null)

/** Блок после резолва: текст собран из атомов, метки — только настоящие. */
data class DocumentBlock(
    val role: BlockRole,
    /** Название реквизита, если оно есть на странице; иначе пусто. */
    val label: String,
    /** Текст блока; у сетки пусто — содержимое живёт в [grid]. */
    val text: String,
    val grid: GroundedTable?,
    /** Сколько первых строк [grid] — заголовки. Вне сетки — `0`. */
    val headerRows: Int,
    /** Метки, которые блок присвоил (после резолва — только настоящие). */
    val ids: Set<String>,
    /** Связь блока со страницей рвалась: выдуманные метки, несвязный набор, диктовка мимо страницы. */
    val flagged: Boolean,
)

/**
 * Документ как набор блоков плюс честный отчёт о том, что в них не попало.
 *
 * Инварианты, которые легко потерять при правке:
 * - **Один разбор, один вызов.** Блоки и сетка приходят в том же ответе, что и ячейки. Второй
 *   вызов «а теперь шапку» — стадия пайплайна, а стадии пайплайна в этом репозитории уже вылезали
 *   в UI лишними кнопками; плюс действие уже платное и медленное, и удвоение на ежедневном
 *   действии — регрессия, а не полнота.
 * - **Непокрытое не молчит.** Атомы вне всех блоков едут в файл видимым блоком [BlockRole.UNREAD]
 *   (его дописывает сам резолв), а не исчезают.
 * - **Слоя нет — контракт тот же.** На рукописи, PDF и тексте блоки приходят
 *   [CellAnswer.Literal]; структура читается всегда, заземление — только там, где есть атомы.
 */
data class DocumentLayout(
    val blocks: List<DocumentBlock>,
    val scope: DocScope?,
    /**
     * Именованные атомы, которых нет ни в одном адресе блока. Пусто ⇔ страница присвоена целиком:
     * каждое прочитанное слово ушло ячейке, блоку вокруг, хрому или «непрочитанному».
     */
    val uncovered: List<Atom>,
    /**
     * Доля именованных атомов в **содержательных** блоках (без [BlockRole.CHROME] и
     * [BlockRole.UNREAD]).
     *
     * `null` — покрытие не считается и не выдумывается: атомов нет вовсе (рукопись) **либо**
     * модель не указала ни на одно слово страницы (ответила дословно, по старому контракту).
     * Во втором случае мы не знаем, что она покрыла, и ноль здесь был бы такой же ложью, как
     * единица.
     */
    val coverage: Float?,
)

/**
 * Сетка документа: сначала [BlockRole.TABLE], потом любая другая содержательная — блок итогов,
 * служебная сетка бланка.
 *
 * «Непрочитанное» и хром сеткой документа не бывают **никогда**, даже если строк в них больше
 * всего: иначе свод чтений проголосовал бы страницу вместо таблицы, а сама подпись
 * «непрочитанное» приехала бы шапкой файла.
 */
val DocumentLayout.grid: GroundedTable? get() = gridBlock()?.grid

/** Сколько первых строк [grid] — заголовки. Сетки нет — `0`. */
val DocumentLayout.gridHeaderRows: Int get() = gridBlock()?.headerRows ?: 0

private fun DocumentLayout.gridBlock(): DocumentBlock? =
    gridIndex().takeIf { it >= 0 }?.let { blocks[it] }

private fun DocumentLayout.gridIndex(): Int =
    blocks.indexOfFirst { it.role == BlockRole.TABLE && it.grid != null }
        .takeIf { it >= 0 }
        ?: blocks.indexOfFirst { it.grid != null && it.role.isContent }

private val BlockRole.isContent: Boolean
    get() = this != BlockRole.CHROME && this != BlockRole.UNREAD

/**
 * Сколько слов уехало «непрочитанным» — то самое число, которым проверяется отмычка.
 *
 * Модель может ссыпать половину документа в [BlockRole.UNREAD] и формально закрыть покрытие.
 * Смягчение — не порог (порог = выдуманное число), а публикация: это число едет в метаданные и
 * в файл видимой строкой, и первый же прогон покажет, если им злоупотребили.
 */
val DocumentLayout.unreadWords: Int
    get() = blocks.filter { it.role == BlockRole.UNREAD }.sumOf { block ->
        block.ids.size.takeIf { it > 0 }
            ?: block.grid?.rows?.sumOf { row -> row.sumOf { it.wordCount() } }
            ?: block.text.wordCount()
    }

private fun String.wordCount(): Int = split(' ', '\n', '\t').count { it.isNotBlank() }

/**
 * Тот же документ, но с другой сеткой: голосование чтений живёт этажом выше ([reconcile]) и
 * возвращает свой результат ровно туда, где сетку нашли, — иначе своднaя таблица приехала бы
 * отдельно от документа, вокруг которого её прочитали.
 *
 * Сетки в документе не было, а голосование её дало (чтения разошлись в структуре) — сетка встаёт
 * блоком [BlockRole.TABLE] последней частью содержания, перед «непрочитанным»: потерять
 * прочитанные строки хуже, чем поставить их не туда.
 */
fun DocumentLayout.withGrid(grid: GroundedTable, headerRows: Int = gridHeaderRows): DocumentLayout {
    val at = gridIndex()
    if (at < 0) {
        if (grid.rows.isEmpty()) return this
        val table = DocumentBlock(
            BlockRole.TABLE, label = "", text = "", grid = grid,
            headerRows = headerRows, ids = emptySet(), flagged = false,
        )
        // Перед «непрочитанным»: оно по смыслу хвост документа, и сетка после него читалась бы
        // как часть непрочитанного.
        val head = blocks.takeWhile { it.role != BlockRole.UNREAD }
        return copy(blocks = head + table + blocks.drop(head.size))
    }
    val updated = blocks[at].copy(grid = grid, headerRows = headerRows)
    return copy(blocks = blocks.toMutableList().also { it[at] = updated })
}

/**
 * Блоки поверх слоя атомов: текст каждого блока собирается [resolveCells] — тем же резолвером,
 * теми же правилами честности.
 *
 * Все ячейки документа резолвятся **одним вызовом**, а не поблочно: гейт диктовки судит по доле
 * подтверждённых страницей чисел во всём ответе (см. `pageWitnesses`), и разбиение на блоки
 * превратило бы одну выборку в десяток крошечных — правило перестало бы работать ровно там, где
 * оно и нужно.
 */
fun AtomLayer.resolveLayout(answer: LayoutAnswer): DocumentLayout = buildLayout(answer, this)

/**
 * Блоки без слоя атомов: указывать не на что, поэтому тексты дословные, а покрытие не считается.
 * Тот же контракт — рукопись, PDF и текст проходят по нему, просто без заземления.
 */
fun literalLayout(answer: LayoutAnswer): DocumentLayout = buildLayout(answer, null)

/** Куда в общем плане ячеек легли части одного блока. */
private class Slice(val label: Int?, val text: Int?, val gridFrom: Int, val gridTo: Int)

private fun buildLayout(answer: LayoutAnswer, layer: AtomLayer?): DocumentLayout {
    val plan = ArrayList<List<CellAnswer>>()
    val slices = answer.blocks.map { block ->
        val label = block.label?.let { plan.add(listOf(it)); plan.size - 1 }
        when (val content = block.content) {
            is BlockContent.Text -> {
                plan.add(listOf(content.cell))
                Slice(label, plan.size - 1, 0, 0)
            }
            is BlockContent.Grid -> {
                val from = plan.size
                plan.addAll(content.cells)
                Slice(label, null, from, plan.size)
            }
        }
    }
    val resolved = layer?.resolveCells(plan) ?: literalCells(plan)
    val index = layer?.atoms?.associateBy { it.id }.orEmpty()

    // Спор чтений у блока вне сетки остаётся пометкой без дропдауна: выбор из вариантов живёт в
    // ячейке таблицы, и заводить его в строке-заголовке значило бы предложить человека выбрать
    // название документа. Пометка при этом никуда не девается — молчания нет.
    val blocks = answer.blocks.mapIndexed { i, block ->
        val slice = slices[i]
        val label = slice.label?.let { resolved.rows[it].firstOrNull() }.orEmpty()
        val text = slice.text?.let { resolved.rows[it].firstOrNull() }.orEmpty()
        val grid = if (block.content is BlockContent.Grid) {
            GroundedTable(
                rows = resolved.rows.subList(slice.gridFrom, slice.gridTo).toList(),
                candidates = resolved.candidates.shiftedTo(slice.gridFrom, slice.gridTo),
                structural = resolved.structural.shiftedTo(slice.gridFrom, slice.gridTo),
            )
        } else {
            null
        }
        val claimed = block.claimedIds(index)
        DocumentBlock(
            role = block.role,
            label = label,
            text = text,
            grid = grid,
            headerRows = (block.content as? BlockContent.Grid)?.headerRows?.coerceAtLeast(0) ?: 0,
            ids = claimed,
            flagged = label.contains('⚠') || text.contains('⚠') ||
                grid?.rows?.any { row -> row.any { it.contains('⚠') } } == true,
        )
    }

    val named = layer?.atoms.orEmpty().filter { it.text.isNotBlank() }
    val claimed = blocks.flatMapTo(HashSet()) { it.ids }
    // Ответ, не указавший ни на одно слово, покрытия не измеряет: мы не знаем, что он покрыл,
    // и «покрыто ноль» здесь было бы такой же выдумкой, как «покрыто всё». Это и есть законный
    // старый контракт — дословная таблица, — и он остаётся ровно таким, каким был.
    val addressed = claimed.isNotEmpty()
    val uncovered = if (addressed) named.filter { it.id !in claimed } else emptyList()
    val content = blocks.filter { it.role != BlockRole.CHROME && it.role != BlockRole.UNREAD }
        .flatMapTo(HashSet()) { it.ids }
    val coverage = when {
        !addressed || named.isEmpty() -> null
        else -> named.count { it.id in content }.toFloat() / named.size
    }
    // Непокрытое едет в файл видимым блоком — это дословная приёмка #266 и единственный способ
    // не соврать, не притворяясь всезнающим. Цена названа: дословная ячейка адреса не занимает,
    // поэтому слово, прочитанное движком и продиктованное моделью, приедет в файл дважды — в
    // ячейке и в «непрочитанном». Повтор человек видит, потерю — нет.
    val tail = if (uncovered.isEmpty() || layer == null) {
        emptyList()
    } else {
        listOf(
            DocumentBlock(
                role = BlockRole.UNREAD,
                label = "",
                text = "",
                grid = GroundedTable(
                    layer.lines(uncovered).map { line -> listOf(line.joinToString(" ") { it.text }) },
                ),
                headerRows = 0,
                ids = uncovered.mapTo(HashSet()) { it.id },
                flagged = false,
            ),
        )
    }
    return DocumentLayout(blocks + tail, answer.scope, uncovered, coverage)
}

/** Метки блока, которые действительно существуют в слое: выдуманные адреса ничего не присваивают. */
private fun BlockAnswer.claimedIds(index: Map<String, Atom>): Set<String> {
    val out = LinkedHashSet<String>()
    fun take(cell: CellAnswer) {
        if (cell is CellAnswer.Ids) cell.ids.filterTo(out) { it in index }
    }
    label?.let(::take)
    when (val c = content) {
        is BlockContent.Text -> take(c.cell)
        is BlockContent.Grid -> c.cells.forEach { row -> row.forEach(::take) }
    }
    return out
}

private fun <T> Map<Pair<Int, Int>, T>.shiftedTo(from: Int, to: Int): Map<Pair<Int, Int>, T> =
    filterKeys { it.first in from until to }
        .mapKeys { (key, _) -> (key.first - from) to key.second }

private fun Set<Pair<Int, Int>>.shiftedTo(from: Int, to: Int): Set<Pair<Int, Int>> =
    filter { it.first in from until to }.mapTo(LinkedHashSet()) { (it.first - from) to it.second }

/**
 * Ячейки без слоя: дословный текст проходит как есть, а адрес — не проходит.
 *
 * Метка при отсутствующем слое ничего не значит: указывать не на что, проверить некому. Чтение
 * модели остаётся (терять прочитанное молча нельзя), но помечено — связь со страницей не
 * подтверждал никто.
 */
private fun literalCells(cells: List<List<CellAnswer>>): GroundedTable = GroundedTable(
    cells.map { row ->
        row.map { cell ->
            when (cell) {
                is CellAnswer.Literal -> cell.text
                is CellAnswer.Ids -> {
                    val text = cell.text?.trim().orEmpty()
                    if (text.contains('⚠')) text else "$text⚠"
                }
            }
        }
    },
)

/** «16×7» — размер сетки. Про таблицу говорит таблица, и её размер заявляется явно. */
const val META_TABLE_GRID = "table.grid"

/** «нет» | «1» | «2» — критична **решённость** вопроса о шапке, а не её наличие. */
const val META_TABLE_HEADER = "table.header"

/** «да» — и только тогда, когда правда: ничего видимого не ушло в файл молча. */
const val META_TABLE_COVERED = "table.covered"

/** Охват страницы, как его заявила модель ([DocScope]). */
const val META_TABLE_SCOPE = "table.scope"

/** Сколько слов уехало «непрочитанным» ([DocumentLayout.unreadWords]). */
const val META_TABLE_UNREAD = "table.unread"

/** Сколько ячеек в файле помечено на проверку. */
const val META_TABLE_FLAGGED = "table.flagged"

/** Как назвать охват человеку: он читает Excel, а не наш словарь. */
fun scopeLabel(scope: DocScope): String = when (scope) {
    DocScope.FULL -> "документ целиком"
    DocScope.VIEWPORT -> "только то, что в кадре"
    DocScope.CROPPED -> "часть документа обрезана"
}

/** Как назвать шапку человеку: «нет» — это ответ, а не пропуск. */
fun headerLabel(headerRows: Int): String = if (headerRows <= 0) "нет" else headerRows.toString()
