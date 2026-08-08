package com.point.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.point.core.flow.Box as PageBox

@Composable
fun SelectionScreen(
    image: ImageBitmap,
    highlights: List<PageBox>,
    capturedText: String?,
    onSelect: (PageBox) -> Unit,
    onTake: () -> Unit,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var container by remember { mutableStateOf(IntSize.Zero) }
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        var dragNow by remember { mutableStateOf<Offset?>(null) }

        val fit = pageFit(container, image.width, image.height)

        val accent = MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { container = it }
                .pointerInput(image) {
                    detectDragGestures(
                        onDragStart = { at -> dragStart = at; dragNow = at },
                        onDragCancel = { dragStart = null; dragNow = null },
                        onDrag = { change, _ -> dragNow = change.position },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragNow
                            dragStart = null
                            dragNow = null
                            if (a != null && b != null) {
                                val from = fit.toPage(a.x, a.y)
                                val to = fit.toPage(b.x, b.y)
                                onSelect(PageBox(from.x, from.y, to.x, to.y))
                            }
                        },
                    )
                }
                .drawWithContent {
                    drawContent()

                    drawPageHighlights(
                        fit = fit,
                        boxes = highlights,
                        color = accent,
                        cornerPx = 4.dp.toPx(),
                        strokePx = 1.5f.dp.toPx(),
                    )

                    val a = dragStart
                    val b = dragNow
                    if (a != null && b != null) {
                        val r = Rect(
                            Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                            Offset(maxOf(a.x, b.x), maxOf(a.y, b.y)),
                        )
                        drawRect(color = accent.copy(alpha = 0.10f), topLeft = r.topLeft, size = r.size)
                        drawRect(
                            color = accent, topLeft = r.topLeft, size = r.size,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .portalCard(shape = SheetShape, elevation = 16.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Захвачено")
            Text(
                text = when {
                    capturedText == null -> "Обведите нужное на странице"

                    capturedText.isBlank() -> "Слов здесь не прочитано — возьмётся фрагмент изображения"
                    else -> capturedText
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (capturedText.isNullOrBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            val canTake = capturedText != null
            PortalRow(
                title = if (capturedText != null && capturedText.isBlank()) "Взять фрагмент" else "Взять",
                onClick = onTake,
                icon = bubbleIcon(SELECT_ICON),
                primary = true,
                chevron = false,
                enabled = canTake,
                modifier = Modifier.graphicsLayer { alpha = if (canTake) 1f else 0.45f },
            )

            // «Смотреть сюда» — Focus, не crop: объект остаётся тем же, Point исследует
            // указанную область (ADR-0001 §10).
            PortalRow(
                title = "Смотреть сюда",
                onClick = onFocus,
                icon = bubbleIcon(FOCUS_ICON),
                chevron = false,
                enabled = canTake,
                modifier = Modifier.graphicsLayer { alpha = if (canTake) 1f else 0.45f },
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val SELECT_ICON = "cutout"

private const val FOCUS_ICON = "find"

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
