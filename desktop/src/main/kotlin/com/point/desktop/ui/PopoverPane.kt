package com.point.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.point.core.flow.ClipboardPayload
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import java.io.File

/**
 * Панель у значка в трее (макет 19.10.2026): «Новый объект» и «Недавнее» без экрана объекта.
 *
 * Это короткая дорога, а не второе окно: объект, выбранный здесь, открывается в окне Point
 * ([onOpen]), и туда же ведёт «Открыть окно». Панель не прячется сама, когда человек уходит в
 * проводник за файлом (владелец 12.08.2026, #546): её закрывают крестиком, Esc или значком трея.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PopoverPane(
    state: DesktopState,
    onOpen: (InboxItem) -> Unit,
    onOpenWindow: () -> Unit,
    onHide: () -> Unit,
    onPickFile: () -> Unit = {},
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},
    onImageDropped: (java.awt.image.BufferedImage) -> Unit = {},
    onClipboardTaken: (ClipboardPayload) -> Unit = { onTextDropped(it.text()) },
    onGrabScreen: (suspend () -> File?)? = null,
    onReceiveFile: () -> Unit = {},
    onCancelReceive: () -> Unit = {},

    /** Человек ещё не вошёл: вход живёт в окне, панель зовёт туда, а не открывает Point в обход. */
    signedOut: Boolean = false,
) {
    val items by state.items.collectAsState()
    val scope = rememberCoroutineScope()
    val ways = remember(state, scope) {
        newObjectWays(state, scope, onPickFile, onClipboardTaken, onGrabScreen, onReceiveFile, onCancelReceive)
    }
    val dropTarget = rememberDropTarget(state, onFilesDropped, onTextDropped, onImageDropped)
    GlassWindow(
        Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .windowKeys { event ->
                val esc = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (esc) onHide()
                esc || newObjectKey(event, ways)
            },
        tone = GlassTone.PANEL,
        shape = RoundedCornerShape(20.dp),
        line = null,
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PointGlyph()
                Text("Point", style = PointType.header)
                PhoneLink(state, Modifier.padding(start = 4.dp).weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).handCursor().clickable(onClick = onOpenWindow)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Открыть окно", style = PointType.small.copy(color = PointColors.link))
                    Icon(GlassIcons.OpenOut, contentDescription = null, tint = PointColors.link, modifier = Modifier.size(12.dp))
                }
                RoundButton(GlassIcons.Close, "Закрыть", onHide)
            }
            if (signedOut) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(com.point.core.flow.SIGN_IN_TITLE, style = PointType.title, textAlign = TextAlign.Center)
                    Text(com.point.core.flow.SIGN_IN_WHY, style = PointType.small, textAlign = TextAlign.Center)
                    LightButton(com.point.core.flow.SIGN_IN_ACTION, onOpenWindow)
                }
            } else {
                RecentPane(state = state, items = items, onOpen = onOpen, ways = ways, modifier = Modifier.weight(1f))
            }
        }
        WindowMessage(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}
