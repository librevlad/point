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

/**
 * Экран выделения (#259): страница целиком, палец рисует рамку, рамка прилипает к словам.
 *
 * Экран нем по части логики: получает битмап и подсветку в **координатах битмапа**, отдаёт
 * рамку жеста в них же — притягивание, страницы и сырой кадр живут у вызывающего. Захваченный
 * текст показывается **до** любых действий (граница #259: кривое выделение видно сразу), и
 * подсветка строится построчно — внешняя рамка многострочного захвата утверждала бы захват
 * слова, которого в адресе нет (ревью #284).
 */
@Composable
fun SelectionScreen(
    image: ImageBitmap,
    highlights: List<PageBox>,
    capturedText: String?,
    onSelect: (PageBox) -> Unit,
    onTake: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var container by remember { mutableStateOf(IntSize.Zero) }
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        var dragNow by remember { mutableStateOf<Offset?>(null) }

        // Вписывание битмапа в контейнер: одна точка правды для рисования и для обратного
        // пересчёта пальца в координаты битмапа — общая с экраном поиска (#279).
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
                    // Построчная подсветка захвата — в координатах контейнера.
                    drawPageHighlights(
                        fit = fit,
                        boxes = highlights,
                        color = accent,
                        cornerPx = 4.dp.toPx(),
                        strokePx = 1.5f.dp.toPx(),
                    )
                    // Живая рамка жеста, пока палец на экране.
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

        // Панель под страницей — поверхность портала, а не `Surface(tonalElevation)` (#461): у
        // Point одна поверхность, и лист бумаги под страницей был вторым языком на том же экране.
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
                    // Путь «непрочитанного» (#259): слов нет, но рамка — честный фрагмент
                    // исходных пикселей, с происхождением. Рукопись обводят именно так.
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
            // «Основное действие» экрана — та же светящаяся строка, что главное действие объекта.
            // Пока пальцем ничего не обведено, брать нечего, и строка притушена, а не перекрашена
            // в серый Material: строка светится, когда может.
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
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Знак «вырезать нужное» из общего словаря — им же подписано «Вырезать» на экране объекта. */
private const val SELECT_ICON = "cutout"

/** Панель приклеена к низу экрана: скругления только сверху. */
private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)