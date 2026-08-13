package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/**
 * Марка на каждый вид объекта (#825).
 *
 * Решение владельца 12.08.2026: **«Марки на все виды сразу»**. Экран объекта должен выглядеть
 * одинаково живым, чем бы объект ни был; таблица Excel была единственной нарисованной маркой,
 * а всё прочее брало плоскую системную иконку — и объект то оживал, то становился строкой из
 * списка приложений.
 *
 * Марки — семья, а не набор картинок. У всех одна плашка: тёмное стекло, неоновая рамка,
 * рождение из ничего. Отличаются цвет и **движение**: у каждого вида своё, и оно говорит, что
 * это за объект, — строки набираются, волна дышит, звенья цепи сцепляются, карточки
 * разъезжаются веером.
 *
 * Рисуется кодом, а не картинками: марка живёт на любом размере и не тянет за собой ресурсы.
 */
enum class KindMark { SPREADSHEET, TEXT, IMAGE, PDF, ARCHIVE, AUDIO, LINK, COLLECTION, DOCUMENT, UNKNOWN }

fun kindMarkOf(kind: ObjectKind, mime: String, name: String? = null): KindMark = when {
    kind == ObjectKind.OFFICE && isSpreadsheet(mime, name) -> KindMark.SPREADSHEET
    kind == ObjectKind.TEXT -> KindMark.TEXT
    kind == ObjectKind.IMAGE -> KindMark.IMAGE
    kind == ObjectKind.PDF -> KindMark.PDF
    kind == ObjectKind.ZIP -> KindMark.ARCHIVE
    kind == ObjectKind.AUDIO -> KindMark.AUDIO
    kind == ObjectKind.URL -> KindMark.LINK
    kind == ObjectKind.COLLECTION -> KindMark.COLLECTION
    kind == ObjectKind.OFFICE -> KindMark.DOCUMENT
    else -> KindMark.UNKNOWN
}

fun kindMarkOf(obj: PointObject): KindMark =
    kindMarkOf(obj.state.kind, obj.mime, obj.metadata["name"])

/**
 * Таблица ли это.
 *
 * Своя проверка, а не общая с android-исходниками: марки живут в шве, который компилируют обе
 * стороны, и тянуть за собой android-файл шов не может.
 */
private fun isSpreadsheet(mime: String, name: String?): Boolean {
    val m = mime.lowercase().substringBefore(';').trim()
    if (m in SPREADSHEET_MIMES) return true
    if (m in DECIDED_OFFICE_MIMES) return false
    return name?.substringAfterLast('.', "")?.lowercase().orEmpty() in SPREADSHEET_EXTS
}

private val SPREADSHEET_MIMES = setOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/vnd.ms-excel.sheet.macroenabled.12",
    "application/vnd.ms-excel.sheet.binary.macroenabled.12",
    "application/vnd.oasis.opendocument.spreadsheet",
    "text/csv",
)

private val DECIDED_OFFICE_MIMES = setOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-powerpoint",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.presentation",
)

private val SPREADSHEET_EXTS = setOf("xlsx", "xls", "xlsm", "xlsb", "ods", "csv")

/** Как марка называется голосовому доступу: тем же словом, что и вид объекта на экране. */
fun kindMarkLabel(mark: KindMark): String = when (mark) {
    KindMark.SPREADSHEET -> "Таблица"
    KindMark.TEXT -> "Текст"
    KindMark.IMAGE -> "Изображение"
    KindMark.PDF -> "PDF"
    KindMark.ARCHIVE -> "Архив"
    KindMark.AUDIO -> "Запись"
    KindMark.LINK -> "Ссылка"
    KindMark.COLLECTION -> "Коллекция"
    KindMark.DOCUMENT -> "Документ"
    KindMark.UNKNOWN -> "Объект"
}

/**
 * Цвета вида.
 *
 * Палитра общая с порталом — неон на тёмном стекле, — но у каждого вида свой тон, чтобы
 * объект узнавался боковым зрением, ещё до подписи.
 */
private data class MarkPalette(val neon: Color, val deep: Color, val glassTop: Color, val glassBottom: Color)

private fun paletteOf(mark: KindMark): MarkPalette = when (mark) {
    KindMark.SPREADSHEET -> MarkPalette(Color(0xFF21E08A), Color(0xFF0B8A50), Color(0xFF11291D), Color(0xFF07130D))
    KindMark.TEXT -> MarkPalette(Color(0xFFB79BFF), Color(0xFF5B3FC7), Color(0xFF1A1630), Color(0xFF0B0916))
    KindMark.IMAGE -> MarkPalette(Color(0xFF4FC3FF), Color(0xFF1668B8), Color(0xFF0F2233), Color(0xFF060F18))
    KindMark.PDF -> MarkPalette(Color(0xFFFF6B5C), Color(0xFFB02A20), Color(0xFF2B1412), Color(0xFF150807))
    KindMark.ARCHIVE -> MarkPalette(Color(0xFFFFC24D), Color(0xFFB37700), Color(0xFF2B2210), Color(0xFF151007))
    KindMark.AUDIO -> MarkPalette(Color(0xFF3FE0D0), Color(0xFF0E8C82), Color(0xFF0E2A29), Color(0xFF061414))
    KindMark.LINK -> MarkPalette(Color(0xFF00A6FF), Color(0xFF0B5FA6), Color(0xFF0D2130), Color(0xFF060E16))
    KindMark.COLLECTION -> MarkPalette(Color(0xFF9B7BFF), Color(0xFF5636C4), Color(0xFF191434), Color(0xFF0A0818))
    KindMark.DOCUMENT -> MarkPalette(Color(0xFF7FA8FF), Color(0xFF2E4FB8), Color(0xFF141C33), Color(0xFF080C18))
    KindMark.UNKNOWN -> MarkPalette(Color(0xFFA8B0C8), Color(0xFF4A5168), Color(0xFF1A1D26), Color(0xFF0B0D12))
}

/**
 * Фазы рождения марки: плашка, содержимое, наполнение, вспышка.
 *
 * Свои, а не общие с `SpreadsheetMark`: та живёт в android-исходниках, а марки видов — в общем
 * шве, который компилируют обе стороны.
 */
private val PLATE = 0f to 0.34f
private val SHAPE = 0.18f to 0.62f
private val FILL = 0.38f to 0.88f
private val SPARK = 0.66f to 1f

private fun phase(progress: Float, from: Float, to: Float): Float =
    if (to <= from) (if (progress >= to) 1f else 0f)
    else ((progress - from) / (to - from)).coerceIn(0f, 1f)

private fun phase(progress: Float, span: Pair<Float, Float>): Float = phase(progress, span.first, span.second)

private const val MARK_LIFE_MS = 900

/**
 * Перетекание одного вида в другой (#825).
 *
 * Превращение — не «пропал один объект, появился другой»: это тот же объект, ставший другим.
 * Поэтому плашка остаётся на месте и перекрашивается, прежний знак уходит, а новый прорастает
 * сквозь него. Снимок, ставший текстом, на глазах теряет рамку и обзаводится строками.
 */
private const val MARK_MORPH_MS = 700

/** Дыхание: марка живёт и после рождения, иначе экран замирает. */
private const val MARK_BREATH_MS = 2600

/**
 * Марка вида объекта.
 *
 * `progress` рождения идёт один раз, дыхание — бесконечно и тихо. Когда движение выключено
 * человеком, марка сразу показывается взрослой и неподвижной.
 */
@Composable
fun KindMarkIcon(
    mark: KindMark,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,

    /**
     * Движётся ли марка. Общий шов не знает, где он выполняется: телефон передаёт сюда
     * системную настройку анимаций, компьютер — свою.
     */
    motion: Boolean = true,
    contentDescription: String = kindMarkLabel(mark),
) {
    val birth = remember(motion) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) birth.animateTo(1f, tween(MARK_LIFE_MS, easing = LinearEasing))
    }

    // Откуда перетекаем. Первый показ превращением не считается: перетекать не из чего.
    var was by remember { mutableStateOf(mark) }
    val morph = remember(motion) { Animatable(1f) }
    LaunchedEffect(mark, motion) {
        if (mark == was) return@LaunchedEffect
        if (motion) {
            morph.snapTo(0f)
            morph.animateTo(1f, tween(MARK_MORPH_MS, easing = LinearEasing))
        }
        was = mark
    }

    val breath by if (motion) {
        rememberInfiniteTransition(label = "mark-breath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MARK_BREATH_MS, easing = EaseInOutSine), RepeatMode.Reverse,
            ),
            label = "mark-breath",
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    val label = contentDescription
    val from = was
    val flow = morph.value
    Canvas(modifier.size(size).semantics { this.contentDescription = label }) {
        drawKindMark(mark, from, flow, birth.value, breath)
    }
}

private fun DrawScope.drawKindMark(
    mark: KindMark,
    from: KindMark,
    flow: Float,
    progress: Float,
    breath: Float,
) {
    val born = EaseOutCubic.transform(phase(progress, PLATE))
    if (born <= 0f) return

    // Плашка не мигает и не рождается заново: она перекрашивается из прежнего вида в новый.
    // Объект остался тем же — он лишь стал другим.
    val palette = blend(paletteOf(from), paletteOf(mark), flow)
    val side = size.minDimension
    val corner = CornerRadius(side * 0.27f)
    scale(0.90f + 0.10f * born, pivot = center) {
        plate(palette, corner, born, breath)

        // Прежний знак уходит, уменьшаясь; новый прорастает сквозь него.
        if (from != mark && flow < 1f) {
            scale(1f - 0.18f * flow, pivot = center) {
                markContent(from, paletteOf(from), progress, born * (1f - flow), breath)
            }
        }
        markContent(mark, paletteOf(mark), progress, born * flow, breath)
    }
}

private fun blend(a: MarkPalette, b: MarkPalette, t: Float) = MarkPalette(
    neon = lerp(a.neon, b.neon, t),
    deep = lerp(a.deep, b.deep, t),
    glassTop = lerp(a.glassTop, b.glassTop, t),
    glassBottom = lerp(a.glassBottom, b.glassBottom, t),
)

private fun DrawScope.markContent(
    mark: KindMark,
    palette: MarkPalette,
    progress: Float,
    born: Float,
    breath: Float,
) {
    if (born <= 0f) return
    when (mark) {
        KindMark.TEXT -> textMark(palette, progress, born)
        KindMark.IMAGE -> imageMark(palette, progress, born, breath)
        KindMark.PDF -> pdfMark(palette, progress, born)
        KindMark.ARCHIVE -> archiveMark(palette, progress, born)
        KindMark.AUDIO -> audioMark(palette, progress, born, breath)
        KindMark.LINK -> linkMark(palette, progress, born, breath)
        KindMark.COLLECTION -> collectionMark(palette, progress, born)
        KindMark.DOCUMENT -> documentMark(palette, progress, born)
        KindMark.UNKNOWN -> unknownMark(palette, progress, born, breath)
        KindMark.SPREADSHEET -> spreadsheetMark(palette, progress, born)
    }
}

/** Таблица: шапка, сетка и строки, набегающие сверху вниз. */
private fun DrawScope.spreadsheetMark(p: MarkPalette, progress: Float, born: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val lay = EaseOutCubic.transform(phase(progress, SHAPE))
    if (lay <= 0f) return
    val headerH = h * 0.24f
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(p.neon, p.deep)),
        topLeft = Offset(pad, pad),
        size = Size(w * lay, headerH),
        cornerRadius = CornerRadius(side * 0.045f),
        alpha = 0.92f * born,
    )
    val rows = 3
    val cols = 3
    val rowH = (h - headerH) / rows
    val colW = w / cols
    repeat(cols - 1) { c ->
        val x = pad + colW * (c + 1)
        drawLine(p.neon, Offset(x, pad + headerH), Offset(x, pad + headerH + (h - headerH) * lay),
            strokeWidth = side * 0.011f, alpha = 0.45f * born)
    }
    repeat(rows) { r ->
        val filled = phase(progress, FILL.first + r * 0.12f, FILL.first + r * 0.12f + 0.4f)
        if (filled <= 0f) return@repeat
        val barH = side * 0.026f
        repeat(cols) { c ->
            val barW = colW * 0.46f
            drawRoundRect(
                color = p.neon,
                topLeft = Offset(pad + colW * c + (colW - barW) / 2f, pad + headerH + rowH * r + (rowH - barH) / 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barH / 2f),
                alpha = 0.55f * filled * born,
            )
        }
    }
}

/** Общая плашка семьи: тёмное стекло, тёплое пятно сверху, неоновая рамка в два свечения. */
private fun DrawScope.plate(p: MarkPalette, corner: CornerRadius, born: Float, breath: Float) {
    val side = size.minDimension
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(p.glassTop, p.glassBottom)),
        cornerRadius = corner,
        alpha = born,
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(p.neon.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(center.x, size.height * 0.30f),
            radius = side * 0.75f,
        ),
        cornerRadius = corner,
        alpha = born,
    )
    listOf(0.13f to 3.2f, 0.72f to 1.0f).forEach { (alpha, widthMul) ->
        drawRoundRect(
            color = p.neon,
            cornerRadius = corner,
            style = Stroke(width = side * 0.012f * widthMul),
            alpha = ((alpha + 0.08f * breath) * born).coerceIn(0f, 1f),
        )
    }
}

private fun DrawScope.contentBox(): Triple<Float, Float, Float> {
    val side = size.minDimension
    return Triple(side * 0.19f, size.width - side * 0.38f, size.height - side * 0.38f)
}

/** Текст: строки набираются слева направо, последняя — короче, как последняя строка абзаца. */
private fun DrawScope.textMark(p: MarkPalette, progress: Float, born: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val lines = 5
    val gap = h / lines
    val thick = side * 0.045f
    repeat(lines) { i ->
        val typed = phase(progress, SHAPE.first + i * 0.11f, SHAPE.first + i * 0.11f + 0.30f)
        if (typed <= 0f) return@repeat
        val full = if (i == lines - 1) w * 0.55f else w * (0.82f + 0.18f * ((i * 7) % 3) / 2f)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(p.neon, p.deep)),
            topLeft = Offset(pad, pad + gap * i + (gap - thick) / 2f),
            size = Size(full * typed, thick),
            cornerRadius = CornerRadius(thick / 2f),
            alpha = (0.55f + 0.45f * typed) * born,
        )
    }
}

/** Снимок: рамка, горизонт с холмом и солнцем; свет медленно проходит по кадру. */
private fun DrawScope.imageMark(p: MarkPalette, progress: Float, born: Float, breath: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val frame = phase(progress, SHAPE)
    if (frame <= 0f) return
    drawRoundRect(
        color = p.neon,
        topLeft = Offset(pad, pad),
        size = Size(w, h * frame),
        cornerRadius = CornerRadius(side * 0.06f),
        style = Stroke(width = side * 0.022f),
        alpha = 0.85f * born,
    )
    val hill = phase(progress, FILL)
    if (hill > 0f) {
        val path = Path().apply {
            moveTo(pad, pad + h)
            lineTo(pad + w * 0.34f, pad + h - h * 0.42f * hill)
            lineTo(pad + w * 0.58f, pad + h - h * 0.16f * hill)
            lineTo(pad + w * 0.78f, pad + h - h * 0.34f * hill)
            lineTo(pad + w, pad + h)
            close()
        }
        drawPath(path, brush = Brush.verticalGradient(listOf(p.neon, p.deep)), alpha = 0.75f * born)
    }
    val sun = phase(progress, SPARK)
    if (sun > 0f) {
        val c = Offset(pad + w * 0.74f, pad + h * 0.28f)
        val r = side * (0.055f + 0.012f * breath)
        drawCircle(p.neon.copy(alpha = 0.28f * sun * born), radius = r * 2.6f, center = c)
        drawCircle(p.neon, radius = r, center = c, alpha = sun * born)
    }
}

/** PDF: страница с загнутым углом; лента с буквами вспыхивает последней. */
private fun DrawScope.pdfMark(p: MarkPalette, progress: Float, born: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val page = phase(progress, SHAPE)
    if (page <= 0f) return
    val fold = side * 0.18f
    val path = Path().apply {
        moveTo(pad, pad)
        lineTo(pad + w - fold, pad)
        lineTo(pad + w, pad + fold)
        lineTo(pad + w, pad + h)
        lineTo(pad, pad + h)
        close()
    }
    drawPath(path, color = p.deep.copy(alpha = 0.55f * page * born))
    drawPath(path, color = p.neon, style = Stroke(width = side * 0.022f), alpha = 0.9f * page * born)

    val corner = Path().apply {
        moveTo(pad + w - fold, pad)
        lineTo(pad + w, pad + fold)
        lineTo(pad + w - fold, pad + fold)
        close()
    }
    drawPath(corner, color = p.neon, alpha = 0.55f * page * born)

    val ribbon = phase(progress, SPARK)
    if (ribbon > 0f) {
        val rh = side * 0.17f
        val rw = w * 0.72f
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(p.neon, p.deep)),
            topLeft = Offset(pad + (w - rw) / 2f, pad + h - rh * 1.35f),
            size = Size(rw, rh),
            cornerRadius = CornerRadius(rh * 0.32f),
            alpha = ribbon * born,
        )
    }
}

/** Архив: короб и застёгивающаяся молния — движение говорит «внутри что-то лежит». */
private fun DrawScope.archiveMark(p: MarkPalette, progress: Float, born: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val box = phase(progress, SHAPE)
    if (box <= 0f) return
    drawRoundRect(
        color = p.deep.copy(alpha = 0.5f * born),
        topLeft = Offset(pad, pad + h * 0.18f),
        size = Size(w, h * 0.82f * box),
        cornerRadius = CornerRadius(side * 0.06f),
    )
    drawRoundRect(
        color = p.neon,
        topLeft = Offset(pad, pad + h * 0.18f),
        size = Size(w, h * 0.82f * box),
        cornerRadius = CornerRadius(side * 0.06f),
        style = Stroke(width = side * 0.022f),
        alpha = 0.9f * born,
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(p.neon, p.deep)),
        topLeft = Offset(pad - side * 0.03f, pad + h * 0.05f),
        size = Size(w + side * 0.06f, h * 0.2f),
        cornerRadius = CornerRadius(side * 0.05f),
        alpha = 0.85f * box * born,
    )

    val zip = phase(progress, FILL)
    val x = pad + w / 2f
    val teeth = 6
    repeat(teeth) { i ->
        val t = phase(zip, i * 0.13f, i * 0.13f + 0.4f)
        if (t <= 0f) return@repeat
        val y = pad + h * 0.30f + (h * 0.62f / teeth) * i
        drawRoundRect(
            color = p.neon,
            topLeft = Offset(x - side * 0.055f, y),
            size = Size(side * 0.11f, side * 0.028f),
            cornerRadius = CornerRadius(side * 0.014f),
            alpha = 0.8f * t * born,
        )
    }
}

/** Запись: полосы волны встают по очереди и потом дышат — звук не бывает неподвижным. */
private fun DrawScope.audioMark(p: MarkPalette, progress: Float, born: Float, breath: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val bars = 7
    val barW = side * 0.052f
    val step = w / bars
    val heights = listOf(0.35f, 0.62f, 0.95f, 0.72f, 1f, 0.5f, 0.28f)
    repeat(bars) { i ->
        val up = phase(progress, SHAPE.first + i * 0.07f, SHAPE.first + i * 0.07f + 0.34f)
        if (up <= 0f) return@repeat
        val wave = 0.86f + 0.14f * kotlin.math.sin((breath + i * 0.18f) * 6.28f)
        val bh = h * heights[i] * up * wave
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(p.neon, p.deep)),
            topLeft = Offset(pad + step * i + (step - barW) / 2f, pad + (h - bh) / 2f),
            size = Size(barW, bh),
            cornerRadius = CornerRadius(barW / 2f),
            alpha = (0.6f + 0.4f * up) * born,
        )
    }
}

/** Ссылка: два звена сцепляются, и по ним пробегает свет. */
private fun DrawScope.linkMark(p: MarkPalette, progress: Float, born: Float, breath: Float) {
    val side = size.minDimension
    val join = EaseOutCubic.transform(phase(progress, SHAPE))
    if (join <= 0f) return
    val r = side * 0.17f
    val gap = side * (0.20f - 0.09f * join)
    val stroke = Stroke(width = side * 0.055f, cap = StrokeCap.Round)
    listOf(-1f, 1f).forEach { dir ->
        drawRoundRect(
            color = p.neon,
            topLeft = Offset(center.x + dir * gap - r, center.y - r * 0.72f),
            size = Size(r * 2f, r * 1.44f),
            cornerRadius = CornerRadius(r * 0.72f),
            style = stroke,
            alpha = (0.85f + 0.15f * breath) * born,
        )
    }
    val spark = phase(progress, SPARK)
    if (spark > 0f) {
        drawCircle(
            p.neon.copy(alpha = 0.35f * spark * born),
            radius = side * 0.22f,
            center = center,
        )
    }
}

/** Коллекция: карточки разъезжаются веером — видно, что объектов несколько. */
private fun DrawScope.collectionMark(p: MarkPalette, progress: Float, born: Float) {
    val side = size.minDimension
    val cards = 3
    repeat(cards) { i ->
        val out = EaseOutCubic.transform(
            phase(progress, SHAPE.first + i * 0.12f, SHAPE.first + i * 0.12f + 0.42f),
        )
        if (out <= 0f) return@repeat
        val shift = side * 0.075f * (i - 1) * out
        val w = side * 0.42f
        val h = side * 0.52f
        drawRoundRect(
            color = p.deep.copy(alpha = 0.75f),
            topLeft = Offset(center.x - w / 2f + shift, center.y - h / 2f - shift * 0.6f),
            size = Size(w, h),
            cornerRadius = CornerRadius(side * 0.06f),
            alpha = born,
        )
        drawRoundRect(
            color = p.neon,
            topLeft = Offset(center.x - w / 2f + shift, center.y - h / 2f - shift * 0.6f),
            size = Size(w, h),
            cornerRadius = CornerRadius(side * 0.06f),
            style = Stroke(width = side * 0.018f),
            alpha = (0.55f + 0.45f * out) * born,
        )
    }
}

/** Документ: страница со строками — те же строки, что у текста, но уже на листе. */
private fun DrawScope.documentMark(p: MarkPalette, progress: Float, born: Float) {
    val (pad, w, h) = contentBox()
    val side = size.minDimension
    val page = phase(progress, SHAPE)
    if (page <= 0f) return
    drawRoundRect(
        color = p.deep.copy(alpha = 0.5f * born),
        topLeft = Offset(pad, pad),
        size = Size(w, h * page),
        cornerRadius = CornerRadius(side * 0.06f),
    )
    drawRoundRect(
        color = p.neon,
        topLeft = Offset(pad, pad),
        size = Size(w, h * page),
        cornerRadius = CornerRadius(side * 0.06f),
        style = Stroke(width = side * 0.022f),
        alpha = 0.9f * born,
    )
    val lines = 4
    repeat(lines) { i ->
        val typed = phase(progress, FILL.first + i * 0.1f, FILL.first + i * 0.1f + 0.3f)
        if (typed <= 0f) return@repeat
        val thick = side * 0.035f
        val inner = w * 0.72f
        drawRoundRect(
            color = p.neon,
            topLeft = Offset(pad + w * 0.14f, pad + h * (0.26f + 0.17f * i)),
            size = Size(inner * typed, thick),
            cornerRadius = CornerRadius(thick / 2f),
            alpha = 0.7f * typed * born,
        )
    }
}

/** Неизвестное: грань, которая ещё не сложилась в фигуру, и тихо пульсирует. */
private fun DrawScope.unknownMark(p: MarkPalette, progress: Float, born: Float, breath: Float) {
    val side = size.minDimension
    val draw = phase(progress, SHAPE)
    if (draw <= 0f) return
    val r = side * 0.24f
    val path = Path()
    val corners = 6
    repeat(corners + 1) { i ->
        val a = (i / corners.toFloat()) * 6.28318f - 1.5708f
        val x = center.x + r * kotlin.math.cos(a)
        val y = center.y + r * kotlin.math.sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color = p.neon,
        style = Stroke(width = side * 0.03f, cap = StrokeCap.Round),
        alpha = (0.5f + 0.35f * breath) * draw * born,
    )
    drawCircle(
        p.neon.copy(alpha = 0.18f * draw * born),
        radius = r * (0.9f + 0.1f * breath),
        center = center,
    )
}
