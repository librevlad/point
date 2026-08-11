package com.point.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.point.core.flow.AtomLayer
import com.point.core.flow.FocusDraft
import com.point.core.flow.FocusPoint
import com.point.core.flow.FocusStroke
import com.point.core.flow.Box as PageBox

/** Чем человек показывает область. Кисть — по умолчанию, остальное для редких случаев. */
enum class FocusTool { BRUSH, RECTANGLE, LASSO, ERASER }

internal val FOCUS_TOOL_LABELS = mapOf(
    FocusTool.BRUSH to "Кисть",
    FocusTool.RECTANGLE to "Прямоугольник",
    FocusTool.LASSO to "Лассо",
    FocusTool.ERASER to "Ластик",
)

internal const val FOCUS_TITLE = "Focus"

internal const val FOCUS_HINT = "Выделите область, в которой находится нужная информация"

/**
 * Focus — отдельный инструмент Point, а не форма перед распознаванием (ТЗ владельца 10.08.2026).
 *
 * Кисть по умолчанию: один свайп показывает область, попадать идеально не нужно — мазок прилипает
 * к содержимому (`FocusDraft`). Прямоугольник и лассо — для случаев, где кисть не подходит.
 * Ластик убирает лишнее. Внутри нет ни предпросмотра распознанного, ни угадывания типов данных:
 * человек показывает место, а не объясняет Point, что там лежит.
 *
 * `✓` — единственное завершение: экран исчезает, наружу уходит область. `×` — отмена.
 */
@Composable
fun FocusScreen(
    image: ImageBitmap,
    layer: AtomLayer? = null,
    onDone: (PageBox, List<PageBox>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(FocusDraft()) }
    var tool by remember { mutableStateOf(FocusTool.BRUSH) }
    var brush by remember { mutableStateOf(DEFAULT_BRUSH) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val fit = pageFit(container, image.width, image.height)
    val accent = MaterialTheme.colorScheme.primary
    val page = PageBox(0f, 0f, image.width.toFloat(), image.height.toFloat())
    // Прилипание к содержимому делает snapSelection на той стороне ✓ — здесь только то,
    // что нарисовал палец, чтобы человек видел ровно своё движение.
    val region = draft.region(pad = SNAP_PAD, page = page)

    // Обведённые места по отдельности (#549): человек мог показать три штуки, и каждая
    // из них — своё место, а не общий прямоугольник, накрывший всё между ними.
    val parts = draft.parts(pad = SNAP_PAD, page = page)

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        FocusTopBar(
            canUndo = draft.canUndo,
            canRedo = draft.canRedo,
            canFinish = region != null,
            onUndo = { draft = draft.undo() },
            onRedo = { draft = draft.redo() },
            onDone = { region?.let { onDone(it, parts) } },
            onCancel = onCancel,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { container = it }
                // Приближение и сдвиг работают всегда, каким бы инструментом ни рисовали.
                .pointerInput(image) {
                    detectTransformGestures(panZoomLock = true) { _, move, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, MAX_ZOOM)
                        pan = if (zoom <= 1f) Offset.Zero else pan + move
                    }
                }
                .pointerInput(image, tool, brush) {
                    detectDragGestures(
                        onDragStart = { at -> live = listOf(at) },
                        onDragCancel = { live = emptyList() },
                        onDrag = { change, _ -> live = live + change.position },
                        onDragEnd = {
                            val drawn = live
                            live = emptyList()
                            strokeOf(drawn, tool, brush, fit, zoom, pan)?.let { draft = draft.add(it) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            )

            // Всё вне выделения затемняется, выделение остаётся полностью видимым.
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    val dim = Color.Black.copy(alpha = DIM)
                    val window = region?.let { shown ->
                        val rect = fit.toScreen(shown)
                        val at = zoomed(rect.topLeft, zoom, pan, Offset(size.width / 2f, size.height / 2f))
                        androidx.compose.ui.geometry.Rect(at, rect.size * zoom)
                    }
                    if (window == null) {
                        drawRect(color = dim)
                    } else {
                        // Темнит всё, КРОМЕ показанного: четыре полосы вокруг окна. Прежде
                        // окно вырезалось BlendMode.Clear — и вместе с затемнением выедало
                        // сам снимок, оставляя чёрную дыру там, где человек только что мазнул.
                        drawRect(color = dim, size = androidx.compose.ui.geometry.Size(size.width, window.top))
                        drawRect(
                            color = dim,
                            topLeft = Offset(0f, window.bottom),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height - window.bottom),
                        )
                        drawRect(
                            color = dim,
                            topLeft = Offset(0f, window.top),
                            size = androidx.compose.ui.geometry.Size(window.left, window.height),
                        )
                        drawRect(
                            color = dim,
                            topLeft = Offset(window.right, window.top),
                            size = androidx.compose.ui.geometry.Size(size.width - window.right, window.height),
                        )
                        drawRoundRect(
                            color = accent,
                            topLeft = window.topLeft,
                            size = window.size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(CORNER),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    if (live.size > 1) {
                        val path = Path().apply {
                            moveTo(live.first().x, live.first().y)
                            live.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path = path,
                            color = if (tool == FocusTool.ERASER) Color.White else accent,
                            style = Stroke(
                                width = brush,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                            alpha = 0.7f,
                        )
                    }
                },
            )

        }

        if (tool != FocusTool.ERASER) {
            BrushSlider(value = brush, onChange = { brush = it })
        }

        FocusTools(
            tool = tool,
            onTool = { tool = it },
            onClear = { draft = draft.cleared() },
        )
        Text(
            text = FOCUS_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FocusTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canFinish: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Отмена", tint = Color.White)
        }
        Text(
            text = FOCUS_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.Filled.Undo,
                contentDescription = "Отменить",
                tint = Color.White.copy(alpha = if (canUndo) 1f else 0.35f),
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.Filled.Redo,
                contentDescription = "Вернуть",
                tint = Color.White.copy(alpha = if (canRedo) 1f else 0.35f),
            )
        }
        Surface(
            color = if (canFinish) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            onClick = onDone,
            enabled = canFinish,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Готово",
                tint = Color.White,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** Панель компактная и документ не съедает: один ряд, без подписей-простыней. */
@Composable
private fun FocusTools(tool: FocusTool, onTool: (FocusTool) -> Unit, onClear: () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FocusTool.entries.forEach { each ->
            ToolChip(
                label = FOCUS_TOOL_LABELS.getValue(each),
                selected = each == tool,
                onClick = { onTool(each) },
            )
        }
        ToolChip(label = "Очистить", selected = false, onClick = onClear)
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Толщина кисти — под документом, где ползунок ничего не закрывает. */
@Composable
private fun BrushSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Кисть", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = MIN_BRUSH..MAX_BRUSH,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Мазок в координатах изображения. Прямоугольник — те же две точки, лассо — весь путь:
 * область одна и та же, отличается только форма показанного.
 */
internal fun strokeOf(
    drawn: List<Offset>,
    tool: FocusTool,
    brush: Float,
    fit: PageFit,
    zoom: Float,
    pan: Offset,
): FocusStroke? {
    if (drawn.isEmpty()) return null
    val points = when (tool) {
        FocusTool.RECTANGLE -> listOfNotNull(drawn.firstOrNull(), drawn.lastOrNull())
        else -> drawn
    }.map { at ->
        val back = unzoomed(at, zoom, pan)
        val page = fit.toPage(back.x, back.y)
        FocusPoint(page.x, page.y)
    }
    val width = when (tool) {
        FocusTool.RECTANGLE -> 0f
        else -> brush / fit.scale.coerceAtLeast(0.0001f) / zoom
    }
    return FocusStroke(points, width = width, erase = tool == FocusTool.ERASER)
}

private fun unzoomed(at: Offset, zoom: Float, pan: Offset): Offset =
    if (zoom == 1f) at else Offset((at.x - pan.x) / zoom, (at.y - pan.y) / zoom)

private fun zoomed(at: Offset, zoom: Float, pan: Offset, center: Offset): Offset =
    if (zoom == 1f) at else Offset((at.x - center.x) * zoom + center.x + pan.x, (at.y - center.y) * zoom + center.y + pan.y)

private const val DIM = 0.62f

private const val CORNER = 10f

private const val MAX_ZOOM = 6f

private const val DEFAULT_BRUSH = 46f

private const val MIN_BRUSH = 16f

private const val MAX_BRUSH = 120f

/** Небольшой запас вокруг найденного: буквы у края не должны срезаться. */
private const val SNAP_PAD = 6f
