package com.point.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.point.core.flow.Box as Place
import com.point.core.model.ObjectKind
import com.point.desktop.InboxItem

/**
 * Снимок загружается один раз на экран: им пользуются и кольцо шапки, и панель содержимого.
 *
 * Кадр стоит так, как его сняли (#1379): метку поворота из заголовка читает сам Point, а не
 * декодер, — тогда то, что видит человек, и то, чем режут и мажут исполнители, связаны одним
 * известным поворотом.
 */
@Composable
internal fun rememberPicture(item: InboxItem): ImageBitmap? =
    remember(item.obj.uri.value, item.obj.state.kind) {
        if (item.obj.state.kind != ObjectKind.IMAGE) {
            null
        } else {
            runCatching {
                val file = java.io.File(item.obj.uri.value)
                val turn = pictureTurn(item)
                if (turn == 0) {
                    file.inputStream().use { androidx.compose.ui.res.loadImageBitmap(it) }
                } else {
                    javax.imageio.ImageIO.read(file)?.let { com.point.desktop.uprightOpaque(it, turn).toComposeImageBitmap() }
                }
            }.getOrNull()
        }
    }

/** На сколько кадр поворачивают, чтобы он встал: 0, 90, 180 или 270. */
internal fun pictureTurn(item: InboxItem): Int =
    com.point.desktop.photoHeadOf(java.io.File(item.obj.uri.value))?.uprightDegrees ?: 0

/**
 * Места, обведённые на снимке, — Focus (#1379), как выделение на телефоне (#736).
 *
 * [places] — в пикселях файла, тех, что режут и мажут исполнители; [turn] — поворот,
 * которым кадр поставлен на экране.
 */
internal class PictureArea(
    val turn: Int,
    val places: List<Place>,
    val onShow: (List<Place>) -> Unit,
    val onClear: () -> Unit,
)

/**
 * Снимок крупно, своей панелью. Мышью на нём обводят место: над ним встают «Взять фрагмент»
 * и «Замазать». С Shift место добавляется к уже обведённым — замазать можно несколько сразу.
 */
@Composable
internal fun PicturePanel(picture: ImageBitmap, area: PictureArea? = null) {
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp))
                    .let { if (area == null) it else it.marking(picture, area) },
            )
        }
        if (area != null) AreaHint(area)
    }
}

@Composable
private fun AreaHint(area: PictureArea) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (area.places.isEmpty()) {
                "Обведите мышью место на снимке — его можно взять фрагментом или замазать"
            } else {
                "С Shift обводится ещё одно место"
            },
            style = PointType.small,
            modifier = Modifier.weight(1f),
        )
        if (area.places.isNotEmpty()) PillButton("Сбросить", area.onClear, small = true)
    }
}

/** Обводка мышью и показ обведённого поверх снимка. */
@Composable
private fun Modifier.marking(picture: ImageBitmap, area: PictureArea): Modifier {
    var view by remember { mutableStateOf(IntSize.Zero) }
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val current by rememberUpdatedState(area)
    val frame = Size(picture.width.toFloat(), picture.height.toFloat())

    return this
        .onSizeChanged { view = it }
        .pointerInput(picture) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val adding = currentEvent.keyboardModifiers.isShiftPressed
                var end = down.position
                drawing = down.position to end
                drag(down.id) { change ->
                    end = change.position
                    drawing = down.position to end
                    change.consume()
                }
                drawing = null
                val fit = fitted(frame, view)
                val shown = fit.toPicture(down.position, end, frame) ?: return@awaitEachGesture
                val area = current
                val raw = rawOf(shown, area.turn, frame)
                area.onShow(if (adding) area.places + raw else listOf(raw))
            }
        }
        .drawWithContent {
            drawContent()
            val fit = fitted(frame, view)
            val boxes = current.places.map { fit.toView(uprightOf(it, current.turn, frame)) } +
                listOfNotNull(drawing?.let { (a, b) -> Place(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)) })
            boxes.forEach { box ->
                val topLeft = Offset(box.left, box.top)
                val size = Size(box.right - box.left, box.bottom - box.top)
                drawRect(PointColors.cyan.copy(alpha = 0.18f), topLeft, size)
                drawRect(PointColors.cyan, topLeft, size, style = Stroke(width = 2.dp.toPx()))
            }
        }
}

/** Где на виджете лежит вписанный снимок: масштаб и отступ. */
private class Fit(val scale: Float, val dx: Float, val dy: Float) {

    fun toView(box: Place) = Place(dx + box.left * scale, dy + box.top * scale, dx + box.right * scale, dy + box.bottom * scale)

    /** Протянутое мышью — в пиксели поставленного кадра; слишком мелкое — случайный клик. */
    fun toPicture(a: Offset, b: Offset, frame: Size): Place? {
        if (scale <= 0f) return null
        fun x(v: Float) = ((v - dx) / scale).coerceIn(0f, frame.width)
        fun y(v: Float) = ((v - dy) / scale).coerceIn(0f, frame.height)
        val box = Place(minOf(x(a.x), x(b.x)), minOf(y(a.y), y(b.y)), maxOf(x(a.x), x(b.x)), maxOf(y(a.y), y(b.y)))
        val tiny = (box.right - box.left) * scale < MIN_DRAG_PX || (box.bottom - box.top) * scale < MIN_DRAG_PX
        return box.takeUnless { tiny }
    }
}

private fun fitted(frame: Size, view: IntSize): Fit {
    if (frame.width <= 0f || frame.height <= 0f || view.width <= 0 || view.height <= 0) return Fit(0f, 0f, 0f)
    val scale = minOf(view.width / frame.width, view.height / frame.height)
    return Fit(scale, (view.width - frame.width * scale) / 2f, (view.height - frame.height * scale) / 2f)
}

/** Размер кадра в файле: при повороте на четверть стороны меняются местами. */
private fun rawSize(frame: Size, turn: Int): Size =
    if (turn % 180 != 0) Size(frame.height, frame.width) else frame

private fun rawOf(shown: Place, turn: Int, frame: Size): Place =
    rawSize(frame, turn).let { com.point.desktop.uprightToRaw(shown, turn, it.width, it.height) }

private fun uprightOf(raw: Place, turn: Int, frame: Size): Place =
    rawSize(frame, turn).let { com.point.desktop.rawToUpright(raw, turn, it.width, it.height) }

private const val MIN_DRAG_PX = 6f
