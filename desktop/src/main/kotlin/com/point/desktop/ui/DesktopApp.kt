package com.point.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File
import com.point.core.model.ObjectKind
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.PcConfig

/**
 * One window: the inbox on the left, the selected object's bubbles inline, and the
 * "connect your phone" card with the QR — the whole pairing story on one screen.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DesktopApp(
    state: DesktopState,
    config: PcConfig,
    addresses: List<String>,
    port: Int,
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},
) {
    val items by state.items.collectAsState()
    val message by state.message.collectAsState()
    val pair by state.pairRequest.collectAsState()
    val clipboardText by state.clipboardText.collectAsState()

    // Native Compose drag&drop (the AWT window.dropTarget never fired — the Compose
    // surface intercepts drops). Reads the OS transferable: files or plain text.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = runCatching {
                val t = event.awtTransferable
                when {
                    t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        @Suppress("UNCHECKED_CAST")
                        onFilesDropped(t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                        true
                    }
                    t.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                        onTextDropped(t.getTransferData(DataFlavor.stringFlavor) as String)
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }
    }

    var showQr by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Полоса окна из мокапа (#285): точка-портал, имя и связь — одной строкой в 44 dp.
            // Крупный заголовок «Point для ПК» ушёл: место на экране принадлежит объекту, а не
            // названию программы, которое человек и так видит в заголовке окна.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(PointColors.window)
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(14.dp)
                        .border(2.dp, PointColors.violet, androidx.compose.foundation.shape.CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text("Point для ПК", style = PointType.small.copy(color = PointColors.muted))
                Spacer(Modifier.width(16.dp))
                message?.let { Text(it, style = PointType.small) }
                Spacer(Modifier.weight(1f))
                ConnectionChip(config, onShowQr = { showQr = true })
            }
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(PointColors.border))
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            // The buffer «lands» visibly (fade + expand) instead of a silent one-off message.
            AnimatedVisibility(visible = clipboardText != null) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    ClipboardCard(
                        text = clipboardText ?: "",
                        onCopyAgain = { state.copyClipboardAgain() },
                        onClose = { state.clearClipboard() },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (items.isEmpty()) {
                // Экран без объекта занят тем, чем работу начать (#285): портал, три способа
                // дать объект и подключение телефона — а не одним лишь QR.
                EmptyScreen(config, addresses, port)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.obj.id }) { item -> ItemCard(state, item) }
                }
            }
            }
        }
    }

    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Подключить телефон") },
            text = { ConnectionCard(config, addresses, port) },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Готово") } },
        )
    }

    pair?.let { request ->
        AlertDialog(
            onDismissRequest = { request.deny() },
            title = { Text("Подключение телефона") },
            text = { Text("Разрешить «${request.deviceName}» отправлять объекты на этот компьютер?") },
            confirmButton = { Button(onClick = { request.allow() }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { request.deny() }) { Text("Отклонить") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(state: DesktopState, item: InboxItem) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                item.obj.metadata["name"] ?: "Объект",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                kindLabelPc(item.obj.state.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val facts = item.obj.metadata.filterKeys { it.startsWith("entity.") }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                facts.entries.take(3).forEach { (k, v) ->
                    Text(
                        "${factLabel(k)}: $v",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.bubblesFor(item).forEach { bubble ->
                    OutlinedButton(onClick = { state.onBubble(item, bubble) }) { Text(bubble.title) }
                }
            }
        }
    }
}

/** The live «Буфер» card — the text that just crossed from the phone into the PC clipboard,
 *  shown instead of a silent one-off message; re-copy or dismiss. */
@Composable
private fun ClipboardCard(text: String, onCopyAgain: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Буфер · Ctrl+V",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCopyAgain) { Text("Копировать снова") }
            TextButton(onClick = onClose) { Text("×") }
        }
    }
}

/** Compact connection status once the desktop is in use — tap to re-show the pairing QR. */
@Composable
private fun ConnectionChip(config: PcConfig, onShowQr: () -> Unit) {
    OutlinedButton(onClick = onShowQr) {
        Text("● ${config.name}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text("QR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ConnectionCard(config: PcConfig, addresses: List<String>, port: Int) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Подключение телефона", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val payload = remember(addresses, port) {
                val host = addresses.firstOrNull() ?: "127.0.0.1"
                // #161 v2: advertise the relay too, so the phone can fall back to it off-LAN.
                val relay = com.point.desktop.RelayEnv.URL.takeIf { it.isNotBlank() }
                com.point.core.flow.PcPairing(host, port, config.token, relay).qrPayload()
            }
            Image(qrImage(payload), contentDescription = "QR для пейринга", modifier = Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White))
            Spacer(Modifier.height(8.dp))
            Text("Point на телефоне → Компьютер →", style = MaterialTheme.typography.bodySmall)
            addresses.forEach {
                Text("$it : $port", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Имя: ${config.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun kindLabelPc(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "Изображение"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "Текст"
    ObjectKind.URL -> "Ссылка"
    ObjectKind.ZIP -> "Архив"
    ObjectKind.OFFICE -> "Документ"
    ObjectKind.COLLECTION -> "Набор"
    else -> "Файл"
}

private fun factLabel(key: String): String = when (key) {
    "entity.phone" -> "Телефон"
    "entity.email" -> "Почта"
    "entity.url" -> "Ссылка"
    "entity.address" -> "Адрес"
    "entity.date" -> "Дата"
    "entity.card" -> "Карта"
    else -> key.removePrefix("entity.")
}
