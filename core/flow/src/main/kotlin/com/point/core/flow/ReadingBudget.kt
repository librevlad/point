package com.point.core.flow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface OcrClock {
    fun nowMs(): Long
}

const val OCR_READ_BUDGET_MS = 180_000L

const val INCOMPLETE_TIMEOUT = "timeout"

class ReadingBudget(private val totalMs: Long, private val clock: OcrClock) {

    init {
        require(totalMs > 0) { "бюджет чтения должен быть положительным, а не $totalMs" }
    }

    private val startedMs = clock.nowMs()

    fun spentMs(): Long = (clock.nowMs() - startedMs).coerceAtLeast(0)

    fun leftMs(): Long = (totalMs - spentMs()).coerceAtLeast(0)

    fun baseCapMs(): Long = (leftMs() - fallbackReserveMs()).coerceAtLeast(0)

    fun fallbackReserveMs(): Long = totalMs / 6
}

class CappedRead(val layer: AtomLayer, val cut: Boolean)

class PlannedReading(val layer: AtomLayer, val angleDegrees: Int)

/**
 * Заход, за который берутся, только пока его ещё ждут (#1242).
 *
 * Страница читается в чужом коде без точек приостановки: отменённое чтение — человек ушёл с
 * объекта или на этот же вопрос ответили сильнее — доходило до конца бюджета и по дороге
 * бралось за следующие заходы, пробы и перечитывания. Плата за них — батарея и время
 * телефона, а результат уже никому не нужен.
 *
 * Спрашивается и после захода, а не только до: движок, остановленный на полуслове, отдаёт
 * обрывок как обычное прочтение, и этот обрывок ложился знанием поверх того, ради чего
 * чтение и прервали. Прерванное чтение знанием не становится вовсе.
 */
private suspend fun ((Int, Long) -> CappedRead).ifWanted(angleDegrees: Int, capMs: Long): CappedRead {
    currentCoroutineContext().ensureActive()
    val read = this(angleDegrees, capMs)
    currentCoroutineContext().ensureActive()
    return read
}

suspend fun readWithBudget(
    budget: ReadingBudget,
    readFull: (angleDegrees: Int, capMs: Long) -> CappedRead,
    readProbe: (angleDegrees: Int, capMs: Long) -> CappedRead,
): PlannedReading {
    val base = readFull.ifWanted(0, budget.baseCapMs())
    if (base.cut) {

        reportStage(FALLBACK_STAGE)
        val small = readProbe.ifWanted(0, budget.leftMs())
        val best = if (readingScore(small.layer) > readingScore(base.layer)) small.layer else base.layer
        return PlannedReading(best.cutShort(), 0)
    }
    if (!looksMisoriented(base.layer)) return PlannedReading(base.layer, 0)

    val tried = LinkedHashMap<Int, AtomLayer>()
    var probesUnfinished = false
    for ((index, angle) in ORIENTATION_ANGLES.withIndex()) {
        val left = budget.leftMs()
        if (left <= 0) {
            probesUnfinished = true
            break
        }

        reportStage(orientationProbeStage(index, ORIENTATION_ANGLES.size))
        val probe = readProbe.ifWanted(angle, left)
        if (probe.cut) probesUnfinished = true
        tried[angle] = probe.layer
    }

    val best = bestOrientation(base.layer, tried)
    if (best == 0) {

        return PlannedReading(if (probesUnfinished) base.layer.cutShort() else base.layer, 0)
    }

    val probeLayer = tried.getValue(best)
    if (budget.leftMs() <= 0) return PlannedReading(probeLayer.cutShort(), best)

    reportStage(REREAD_STAGE)
    val full = readFull.ifWanted(best, budget.leftMs())
    return when {
        !full.cut -> PlannedReading(full.layer, best)

        readingScore(full.layer) > readingScore(probeLayer) -> PlannedReading(full.layer.cutShort(), best)
        else -> PlannedReading(probeLayer.cutShort(), best)
    }
}

fun orientationProbeStage(index: Int, total: Int): String =
    "Пробую повернуть страницу — ${index + 1} из $total"

const val FALLBACK_STAGE = "Страница большая — читаю упрощённо"

const val REREAD_STAGE = "Нашёл, как лежит страница — перечитываю"

private fun AtomLayer.cutShort(): AtomLayer =
    AtomLayer(atoms, readerText = readerText, transform = transform, incomplete = INCOMPLETE_TIMEOUT)

fun ocrDoneLine(layer: AtomLayer, spentMs: Long): String {
    val note = layer.incomplete?.let { " ($it)" } ?: ""

    val zoom = layer.transform?.upscale?.takeIf { it > 1 }?.let { ", upscale x$it" } ?: ""
    return "OCR done: ${layer.atoms.size} words, ${layer.text.length} chars, $spentMs ms$zoom$note"
}
