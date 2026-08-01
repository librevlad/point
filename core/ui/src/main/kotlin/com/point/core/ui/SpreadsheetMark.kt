package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.ui.theme.PointTheme

/*
 * Знак таблицы (#295).
 *
 * Живой прецедент: человек жмёт «В Excel», ждёт минуту сетевого действия — и получает экран, на
 * котором результат выглядит ровно так же, как любой docx или pptx: общая иконка документа
 * (`Icons.Filled.Article`). Работа произошла, объект стал другим, а сказать об этом на экране
 * нечем. Знак — это ответ на «что это теперь»: лист с шапкой, сеткой и загоревшимся «X».
 *
 * Рисуется Canvas'ом в языке портала (`Portal.kt`): своя тёмная сцена, независимая от системной
 * темы, поддельный bloom в два прохода (широкий тусклый + узкий яркий) и тот же белый сердечник
 * [PortalCore]. Зелень — неоновый родственник `bubbleColor("excel")`, чтобы действие и его
 * результат читались как одна вещь. Ни растровых ассетов, ни внешних библиотек.
 */

/** Неоновая зелень знака — родня `bubbleColor("excel")` (#15803D), но в яркости портала. */
private val SheetNeon = Color(0xFF21E08A)
private val SheetNeonDeep = Color(0xFF0B8A50)
private val SheetGlassTop = Color(0xFF11291D)
private val SheetGlassBottom = Color(0xFF07130D)

/** Тот же трюк, что у портала: широкий тусклый проход + узкий яркий = свечение без блюра. */
private val SheetBloom = listOf(0.13f to 3.2f, 0.72f to 1.0f)

private const val COLUMNS = 3
private const val ROWS = 3

/** Рождение знака целиком, мс: лист → шапка → строки → «X». Ничего не зациклено. */
private const val MARK_BIRTH_MS = 900

/**
 * Какой знак носит объект, у которого нет собственного превью.
 *
 * [GENERIC] — общая иконка типа (`kindIcon`), она же поведение до #295.
 */
enum class ObjectMark { SPREADSHEET, GENERIC }

/**
 * Выбор знака по тому, **что объект есть** (#295).
 *
 * Решает [kind]: знак таблицы носит только `OFFICE` — картинка с именем `таблица.xlsx` остаётся
 * картинкой, потому что вид объекта в Point первичен, а MIME лишь уточняет, какой это документ.
 *
 * Живой прецедент, из-за которого одного MIME мало: OOXML — это zip, и в Share он регулярно
 * приезжает как `application/octet-stream` или `application/zip`. `ObjectClassifier` уже поэтому
 * смотрит на расширение — здесь та же развилка, иначе половина реальных .xlsx получала бы общий
 * знак. Но точный офисный MIME сильнее имени: `.xlsx`, приписанный к docx-документу, не должен
 * переодевать документ в таблицу.
 */
fun objectMark(kind: ObjectKind, mime: String, name: String? = null): ObjectMark =
    if (kind == ObjectKind.OFFICE && isSpreadsheet(mime, name)) ObjectMark.SPREADSHEET
    else ObjectMark.GENERIC

/** Тот же выбор для готового объекта — имя лежит в метаданных под ключом `name`. */
fun objectMark(obj: PointObject): ObjectMark =
    objectMark(obj.state.kind, obj.mime, obj.metadata["name"])

private fun isSpreadsheet(mime: String, name: String?): Boolean {
    val m = mime.lowercase().substringBefore(';').trim()
    if (m in SPREADSHEET_MIMES) return true
    // Документ назвал себя сам и назвал не таблицей — имя файла тут уже не спорит.
    if (m in DECIDED_OFFICE_MIMES) return false
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return ext in SPREADSHEET_EXTS
}

private val SPREADSHEET_MIMES = setOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/vnd.ms-excel.sheet.macroenabled.12",
)
private val SPREADSHEET_EXTS = setOf("xlsx", "xls", "xlsm")

/** Офисные MIME, которые уже сказали, что это НЕ таблица (см. `ObjectClassifier.OFFICE_MIMES`). */
private val DECIDED_OFFICE_MIMES = setOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/msword",
    "application/vnd.ms-powerpoint",
)

/**
 * Знак таблицы: стеклянный лист, шапка, сетка и «X» в углу.
 *
 * Появление — одноразовое рождение (MOTION.md, принцип №1 «ничего не появляется мгновенно»), и
 * каждая фаза что-то значит: лист проявляется → слева направо ложится шапка → строка за строкой
 * наполняются ячейки → последним загорается «X». Это буквально рассказ «данные легли в таблицу,
 * и это Excel». Ничего не крутится и не повторяется: после ~[MARK_BIRTH_MS] мс знак стоит.
 *
 * Экран это не задерживает — рисование идёт поверх уже интерактивного экрана (инвариант ≤300 мс).
 * При выключенной системой анимации ([rememberMotionEnabled]) знак сразу статичен и дорисован.
 */
@Composable
fun SpreadsheetMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String = "Таблица Excel",
) {
    val motion = rememberMotionEnabled()
    val birth = remember(motion) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) birth.animateTo(1f, tween(MARK_BIRTH_MS, easing = LinearEasing))
    }
    val label = contentDescription
    Canvas(
        modifier
            .size(size)
            .semantics { this.contentDescription = label },
    ) {
        drawSpreadsheetMark(birth.value)
    }
}

/** Фаза рождения знака — доли общего времени [MARK_BIRTH_MS]. */
internal data class MarkPhase(val from: Float, val to: Float)

/** Порядок фаз — это и есть смысл анимации («данные легли → это Excel»), поэтому он живёт
 *  именованными константами и проверяется тестом, а не прячется числами внутри рисования. */
internal val MARK_SHEET = MarkPhase(0f, 0.34f)
internal val MARK_GRID = MarkPhase(0.18f, 0.62f)
internal val MARK_ROWS = MarkPhase(0.38f, 0.88f)
internal val MARK_IGNITE = MarkPhase(0.66f, 1f)

/** Доля отрезка [from]..[to], пройденная к моменту [progress] — 0 до начала, 1 после конца. */
internal fun markSegment(progress: Float, from: Float, to: Float): Float =
    if (to <= from) (if (progress >= to) 1f else 0f)
    else ((progress - from) / (to - from)).coerceIn(0f, 1f)

internal fun markSegment(progress: Float, phase: MarkPhase): Float =
    markSegment(progress, phase.from, phase.to)

private fun DrawScope.drawSpreadsheetMark(progress: Float) {
    val side = size.minDimension
    val born = EaseOutCubic.transform(markSegment(progress, MARK_SHEET))
    if (born <= 0f) return
    val lay = EaseOutCubic.transform(markSegment(progress, MARK_GRID)) // шапка и сетка
    val land = markSegment(progress, MARK_ROWS)                        // строки наполняются
    val ignite = markSegment(progress, MARK_IGNITE)                    // «X» загорается

    // Радиус скругления держим долей стороны — знак живёт и в 96dp героя, и мельче.
    val corner = CornerRadius(side * 0.27f)
    scale(0.90f + 0.10f * born, pivot = center) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(SheetGlassTop, SheetGlassBottom)),
            cornerRadius = corner,
            alpha = born,
        )
        // Свет изнутри стекла — портал светит сквозь лист.
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(SheetNeon.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(center.x, size.height * 0.30f),
                radius = side * 0.75f,
            ),
            cornerRadius = corner,
            alpha = born,
        )
        for ((alpha, widthMul) in SheetBloom) {
            drawRoundRect(
                color = SheetNeon,
                cornerRadius = corner,
                style = Stroke(width = side * 0.012f * widthMul),
                alpha = (alpha * born).coerceIn(0f, 1f),
            )
        }

        val pad = side * 0.17f
        val contentW = size.width - pad * 2f
        val contentH = size.height - pad * 2f
        val headerH = contentH * 0.26f
        val rowH = (contentH - headerH) / ROWS
        val colW = contentW / COLUMNS
        val hairline = side * 0.011f

        // Шапка ложится слева направо — таблица начинается с заголовков.
        if (lay > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(SheetNeon, SheetNeonDeep)),
                topLeft = Offset(pad, pad),
                size = Size(contentW * lay, headerH),
                cornerRadius = CornerRadius(side * 0.045f),
                alpha = 0.92f * born,
            )
        }

        // Сетка: линии прорастают вниз/вправо вместе с шапкой.
        val gridTop = pad + headerH
        val gridH = (contentH - headerH) * lay
        for (c in 1 until COLUMNS) {
            val x = pad + colW * c
            drawLine(
                color = SheetNeon,
                start = Offset(x, gridTop),
                end = Offset(x, gridTop + gridH),
                strokeWidth = hairline,
                alpha = 0.45f * born,
            )
        }
        for (r in 1 until ROWS) {
            val y = gridTop + rowH * r
            drawLine(
                color = SheetNeon,
                start = Offset(pad, y),
                end = Offset(pad + contentW * lay, y),
                strokeWidth = hairline,
                alpha = 0.45f * born,
            )
        }

        // Строка за строкой в ячейки ложатся значения.
        for (r in 0 until ROWS) {
            val filled = markSegment(land, r * 0.18f, r * 0.18f + 0.64f)
            if (filled <= 0f) continue
            val rowTop = gridTop + rowH * r
            drawRect(
                color = SheetNeon,
                topLeft = Offset(pad, rowTop),
                size = Size(contentW, rowH),
                alpha = 0.12f * filled * born,
            )
            for (c in 0 until COLUMNS) {
                val barW = colW * 0.44f
                val barH = side * 0.022f
                drawRoundRect(
                    color = SheetNeon,
                    topLeft = Offset(pad + colW * c + (colW - barW) / 2f, rowTop + (rowH - barH) / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                    alpha = 0.5f * filled * born,
                )
            }
        }

        drawExcelBadge(ignite * born)
    }
}

/**
 * «X» в углу листа: последний штрих рождения — теперь это не просто таблица, а Excel.
 *
 * Плитка, а не кружок: круглая подложка с крестом внутри читается как кнопка «закрыть» — это
 * видно на первом же рендере, — а скруглённый квадрат читается как знак приложения.
 */
private fun DrawScope.drawExcelBadge(t: Float) {
    if (t <= 0f) return
    val side = size.minDimension
    val r = side * 0.165f
    val c = Offset(size.width - side * 0.225f, size.height - side * 0.225f)
    val pop = 0.55f + 0.45f * EaseOutBack.transform(t)
    val half = r * pop
    val tile = CornerRadius(half * 0.62f)

    drawCircle(
        brush = Brush.radialGradient(
            listOf(SheetNeon.copy(alpha = 0.55f), Color.Transparent),
            center = c,
            radius = r * 2.2f,
        ),
        radius = r * 2.2f,
        center = c,
        alpha = t,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(SheetNeon, SheetNeonDeep), startY = c.y - r, endY = c.y + r),
        topLeft = Offset(c.x - half, c.y - half),
        size = Size(half * 2f, half * 2f),
        cornerRadius = tile,
        alpha = t,
    )
    drawRoundRect(
        color = PortalCore,
        topLeft = Offset(c.x - half, c.y - half),
        size = Size(half * 2f, half * 2f),
        cornerRadius = tile,
        style = Stroke(side * 0.012f),
        alpha = 0.35f * t,
    )

    val arm = r * 0.50f * pop
    val width = r * 0.26f
    // Тот же белый сердечник, что у портала, с мягким ореолом вокруг него.
    for ((alpha, widthMul) in listOf(0.20f to 1.9f, 1f to 1f)) {
        val a = (alpha * t).coerceIn(0f, 1f)
        drawLine(
            color = PortalCore,
            start = c + Offset(-arm, -arm),
            end = c + Offset(arm, arm),
            strokeWidth = width * widthMul,
            cap = StrokeCap.Round,
            alpha = a,
        )
        drawLine(
            color = PortalCore,
            start = c + Offset(-arm, arm),
            end = c + Offset(arm, -arm),
            strokeWidth = width * widthMul,
            cap = StrokeCap.Round,
            alpha = a,
        )
    }
}

@Preview(name = "Знак таблицы · сам знак", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewSpreadsheetMark() = PointTheme(darkTheme = true) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SpreadsheetMark(size = 132.dp)
    }
}

@Preview(name = "Знак таблицы · внутри портала (герой)", showBackground = true, backgroundColor = 0xFF07070C)
@Composable
private fun PreviewSpreadsheetMarkInPortal() = PointTheme(darkTheme = true) {
    // Ровно композиция шапки экрана объекта: знак стоит в кольце портала.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Portal(size = 164.dp)
        SpreadsheetMark(size = 96.dp)
    }
}
