package com.point.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.point.core.flow.ClipboardPayload
import com.point.desktop.DesktopState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Части окна, общие для окна в две панели и панели у трея: рамка окна без рамки ОС, способы
 * взять новый объект, их клавиши и приём брошенного.
 */

/**
 * Что окно ОС даёт шапке Point: за неё окно тянут, ею его сворачивают и разворачивают.
 *
 * Окно без рамки системы рисует свою шапку (макет 19.10.2026), и всё, что делала рамка, обязано
 * остаться: перетащить, свернуть, развернуть. В тестах и в превью окна ОС нет — тогда шапка
 * просто не показывает кнопок, которым нечего делать.
 */
class WindowChrome(
    val dragArea: @Composable (Modifier, @Composable () -> Unit) -> Unit = { modifier, content -> Box(modifier) { content() } },
    val onMinimize: (() -> Unit)? = null,
    val onToggleMaximize: (() -> Unit)? = null,
    val maximized: Boolean = false,

    /** Уголок, за который окно растягивают: у окна без рамки ОС краёв для этого нет. */
    val resizeGrip: (@Composable (Modifier) -> Unit)? = null,
)

/**
 * Уголок в правом нижнем углу окна: тянут — окно меняет размер. Движение меряется по экрану,
 * а не внутри окна: уголок едет вместе с краем, и внутренние координаты убегали бы из-под руки.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ResizeGrip(size: () -> androidx.compose.ui.unit.DpSize, onSize: (androidx.compose.ui.unit.DpSize) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier.size(18.dp)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.SE_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val start = java.awt.MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val from = size()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val at = java.awt.MouseInfo.getPointerInfo()?.location ?: break
                        event.changes.forEach { it.consume() }
                        onSize(androidx.compose.ui.unit.DpSize(from.width + (at.x - start.x).dp, from.height + (at.y - start.y).dp))
                    }
                }
            },
    ) {
        val ink = PointColors.text.copy(alpha = 0.28f)
        val w = this.size.width
        drawLine(ink, Offset(w * 0.35f, w * 0.85f), Offset(w * 0.85f, w * 0.35f), 1.2.dp.toPx())
        drawLine(ink, Offset(w * 0.6f, w * 0.85f), Offset(w * 0.85f, w * 0.6f), 1.2.dp.toPx())
    }
}

/** Способы взять новый объект — одни на окно и на панель у трея. */
internal fun newObjectWays(
    state: DesktopState,
    scope: CoroutineScope,
    onPickFile: () -> Unit,
    onClipboardTaken: (ClipboardPayload) -> Unit,
    onGrabScreen: (suspend () -> File?)?,
    onReceiveFile: () -> Unit,
    onCancelReceive: () -> Unit,
): NewObjectWays = NewObjectWays(
    pickFile = onPickFile,

    // Буфер берётся целиком, а не только текстом (#1370): область экрана, снятая Win+Shift+S,
    // лежит в нём картинкой.
    takeClipboard = {
        val brought = runCatching { com.point.desktop.readSystemClipboard() }.getOrNull()
        if (com.point.desktop.worthTaking(brought)) onClipboardTaken(brought!!) else state.say("В буфере пусто")
    },

    // Снимок ждёт, пока окна Point уйдут с экрана: иначе на снимке окажется сам Point. Объектом
    // его делает сам [onGrabScreen] — снятое здесь, а не брошенное в окно.
    grabScreen = {
        scope.launch {
            if (onGrabScreen?.invoke() == null) state.say("Снять экран не вышло")
        }
    },
    receiveFile = onReceiveFile,
    copyReceiveLink = { link -> state.copyFact(link) },
    cancelReceive = onCancelReceive,
    clipboardNote = { clipboardNote(runCatching { com.point.desktop.readSystemClipboard() }.getOrNull()) },
)

/** Что лежит в буфере — словами, рядом с пунктом «Из буфера»: «текст, 2 строки», «картинка». */
internal fun clipboardNote(payload: ClipboardPayload?): String = when {
    payload == null || !com.point.desktop.worthTaking(payload) -> "пусто"
    payload.isText -> payload.text().trim().lines().size.let { n -> "текст, $n " + lineWord(n) }
    payload.isImage -> "картинка"
    else -> payload.name.ifBlank { "файл" }
}

private fun lineWord(n: Int): String = when {
    n % 100 in 11..14 -> "строк"
    n % 10 == 1 -> "строка"
    n % 10 in 2..4 -> "строки"
    else -> "строк"
}

/** Клавиши нового объекта: Ctrl+O — файл, Ctrl+V — буфер, Ctrl+Shift+S — снимок экрана. */
internal fun newObjectKey(event: KeyEvent, ways: NewObjectWays): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false

    // Ctrl+V — как везде на компьютере; корень не слышит его, когда человек печатает в поле
    // (#1025), поэтому Shift здесь не страховка, а лишний палец.
    when {
        event.key == Key.O && !event.isShiftPressed -> ways.pickFile()
        event.key == Key.V -> ways.takeClipboard()
        event.key == Key.S && event.isShiftPressed -> ways.grabScreen()
        else -> return false
    }
    return true
}


/**
 * Приём брошенного в окно: файлы, текст, картинка со страницы. Пачку Point не открывает за
 * человека — она ложится списком ([onBatch]).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun rememberDropTarget(
    state: DesktopState,
    onFilesDropped: (List<File>) -> Unit,
    onTextDropped: (String) -> Unit,
    onImageDropped: (java.awt.image.BufferedImage) -> Unit,
    onBatch: (Boolean) -> Unit = {},
): DragAndDropTarget = remember(state) {
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val brought = runCatching { com.point.desktop.readDropped(event.awtTransferable) }
                .getOrElse { com.point.desktop.Dropped.NotTaken(com.point.desktop.DROP_UNREADABLE) }
            onBatch(com.point.desktop.droppedAsBatch(brought))
            return com.point.desktop.takeDropped(
                brought,
                files = onFilesDropped,
                text = onTextDropped,
                picture = onImageDropped,
                say = state::say,
            )
        }
    }
}
