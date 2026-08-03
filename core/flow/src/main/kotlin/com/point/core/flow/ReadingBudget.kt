package com.point.core.flow

/**
 * Предел времени чтения страницы (#262): чтение, которое не кончается, — молчаливый провал.
 *
 * Живой прогон корпуса это показал буквально: на фото приборов и перевёрнутой ведомости строка
 * `OCR done` не появилась за 3+ минуты — базовое чтение 12-мегапиксельного кадра плюс три пробы
 * поворотов в том же полном размере, и ни у одного шага нет предела. Снаружи такое неотличимо от
 * «ещё думает», и харнесс (а завтра человек) ждёт зря.
 *
 * Лекарство — не «быстрее читать», а честный бюджет: у всего чтения один предел; базовое чтение
 * получает не больше половины (пробам обязано оставаться время); пробы идут на уменьшенной копии
 * и в остатке бюджета; по истечении отдаётся **то, что успели**, с названной причиной
 * ([AtomLayer.incomplete]), а строка `OCR done` печатается всегда ([ocrDoneLine]).
 *
 * Часы — за швом [OcrClock]: предел обязан проверяться рукой в тесте, а не сном потока.
 */
fun interface OcrClock {
    fun nowMs(): Long
}

/** Общий предел одного чтения страницы: базовое чтение + пробы поворотов + дочитывание. */
const val OCR_READ_BUDGET_MS = 180_000L

/** Причина неполноты слоя, когда чтение упёрлось в предел времени. */
const val INCOMPLETE_TIMEOUT = "timeout"

/**
 * Бюджет одного чтения. Считает потраченное от своего создания; [leftMs] не уходит в минус —
 * «осталось −3 секунды» превратило бы колпак следующего шага в мусорное отрицательное число.
 */
class ReadingBudget(private val totalMs: Long, private val clock: OcrClock) {

    init {
        require(totalMs > 0) { "бюджет чтения должен быть положительным, а не $totalMs" }
    }

    private val startedMs = clock.nowMs()

    fun spentMs(): Long = (clock.nowMs() - startedMs).coerceAtLeast(0)

    fun leftMs(): Long = (totalMs - spentMs()).coerceAtLeast(0)

    /**
     * Колпак базового чтения — не больше половины бюджета. Не оптимизация, а гарантия: пробы
     * поворотов обязаны укладываться в общий предел, значит базовое чтение не имеет права
     * съесть его целиком (на кадрах корпуса именно оно и было вечным).
     */
    fun baseCapMs(): Long = minOf(leftMs(), totalMs / 2)
}

/**
 * Одно чтение движка под колпаком времени: слой плюс правда о том, был ли движок остановлен.
 *
 * Остановленное чтение отдаёт пустой слой (см. [readWithBudget]): официальный Tesseract после
 * отмены результат не читает — недочитанные слова в странице без результата, и обход их падает.
 * Пустота с пометкой честнее правдоподобного огрызка без неё.
 */
class CappedRead(val layer: AtomLayer, val cut: Boolean)

/** Итог чтения с пробами: выбранный слой и доворот, которым он прочитан (0 — исходный кадр). */
class PlannedReading(val layer: AtomLayer, val angleDegrees: Int)

/**
 * Чтение страницы с пробами ориентации под общим бюджетом. Чистая оркестровка: движок приходит
 * двумя лямбдами (`(уголГрадусов, колпакМс) → чтение`), поэтому предел тестируется фейковым
 * медленным движком без устройства.
 *
 * - [readFull] — чтение полного кадра (базовое и дочитывание победившего доворота);
 * - [readProbe] — проба поворота на **уменьшенной копии**: пробе не нужны точные буквы, ей нужен
 *   счёт [readingScore], а вчетверо меньше пикселей — вчетверо дешевле проход. Слой пробы при
 *   этом адресно честен (его [AtomLayer.transform] помнит масштаб копии), поэтому годится и как
 *   итог, когда дочитать полный кадр уже не успеваем.
 *
 * Правила бюджета:
 * 1. базовое чтение — под колпаком [ReadingBudget.baseCapMs];
 * 2. отрезанное базовое чтение проб **не открывает**: сравнивать повороты не с чем, и любой
 *    случайный счёт мусора «выиграл» бы у пустоты — подмена системы координат ради шума;
 * 3. каждая проба — в остатке бюджета; кончился — оставшиеся пропускаются;
 * 4. победивший доворот дочитывается полным кадром, если время осталось; не осталось или
 *    дочитывание отрезали — итогом становится слой пробы;
 * 5. всё, что вышло короче задуманного, несёт причину [INCOMPLETE_TIMEOUT] — частичный ответ
 *    без пометки был бы тихой ложью, как и пустота без ответа.
 */
fun readWithBudget(
    budget: ReadingBudget,
    readFull: (angleDegrees: Int, capMs: Long) -> CappedRead,
    readProbe: (angleDegrees: Int, capMs: Long) -> CappedRead,
): PlannedReading {
    val base = readFull(0, budget.baseCapMs())
    if (base.cut) return PlannedReading(base.layer.cutShort(), 0)
    if (!looksMisoriented(base.layer)) return PlannedReading(base.layer, 0)

    val tried = LinkedHashMap<Int, AtomLayer>()
    var probesUnfinished = false
    for (angle in ORIENTATION_ANGLES) {
        val left = budget.leftMs()
        if (left <= 0) {
            probesUnfinished = true
            break
        }
        val probe = readProbe(angle, left)
        if (probe.cut) probesUnfinished = true
        tried[angle] = probe.layer
    }

    val best = bestOrientation(base.layer, tried)
    if (best == 0) {
        // Никто не выиграл заметно — остаёмся в исходном чтении. Но если пробы не дожили до
        // конца, «не выиграл» может означать «не успел», и слой обязан это сказать.
        return PlannedReading(if (probesUnfinished) base.layer.cutShort() else base.layer, 0)
    }

    val probeLayer = tried.getValue(best)
    if (budget.leftMs() <= 0) return PlannedReading(probeLayer.cutShort(), best)
    val full = readFull(best, budget.leftMs())
    return when {
        !full.cut -> PlannedReading(full.layer, best)
        // Дочитывание отрезали: отдаём лучшее из двух чтений победившего угла — обычно пробу
        // (отрезанный движок отдаёт пусто), но правило сравнением, а не верой в реализацию.
        readingScore(full.layer) > readingScore(probeLayer) -> PlannedReading(full.layer.cutShort(), best)
        else -> PlannedReading(probeLayer.cutShort(), best)
    }
}

/** Тот же слой с причиной «упёрлись в предел времени». */
private fun AtomLayer.cutShort(): AtomLayer =
    AtomLayer(atoms, readerText = readerText, transform = transform, incomplete = INCOMPLETE_TIMEOUT)

/**
 * Строка `OCR done` — печатается **всегда**, чем бы чтение ни кончилось (#262).
 *
 * По ней харнесс корпуса (и человек в logcat) узнаёт, что чтение завершилось; чтение без этой
 * строки снаружи неотличимо от «ещё думает». Поэтому строит её одна функция, под тестом: успех,
 * таймаут, нечитаемый кадр — различаются пометкой в скобках, а не наличием строки.
 */
fun ocrDoneLine(layer: AtomLayer, spentMs: Long): String {
    val note = layer.incomplete?.let { " ($it)" } ?: ""
    return "OCR done: ${layer.atoms.size} words, ${layer.text.length} chars, $spentMs ms$note"
}
