package com.point.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DesktopApp(
    state: DesktopState,
    config: PcConfig,

    account: com.point.desktop.DesktopAccount,
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},

    onClipboardTaken: (String) -> Unit = onTextDropped,

    onGrabScreen: (() -> File?)? = null,

    onWipe: () -> Unit = {},

    onSaveSettings: (PcConfig) -> Unit = {},

    onSweepNow: () -> Unit = {},
) {
    val items by state.items.collectAsState()
    val message by state.message.collectAsState()
    val clipboardText by state.clipboardText.collectAsState()
    val lastContact by state.lastContact.collectAsState()
    val journal by state.journal.collectAsState()

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

    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(config) }
    val signIn by account.signIn.collectAsState()
    val circle by account.circle.collectAsState()
    val accountBusy by account.busy.collectAsState()
    val accountError by account.error.collectAsState()

    LaunchedEffect(signIn) { if (signIn == null) account.refreshCircle() }

    val takeClipboard = {
        val text = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrBlank()) state.say("В буфере пусто") else onClipboardTaken(text)
    }

    val grabScreen = {
        val file = onGrabScreen?.invoke()
        if (file == null) state.say("Снять экран не вышло") else onFilesDropped(listOf(file))
    }
    // Без сфокусированного узла Compose не доставляет клавиатуру — Ctrl+Shift+V молчал
    // на любом экране (живой прогон 2026-08-09), хотя подсказки его обещали.
    val hotkeys = remember { FocusRequester() }
    LaunchedEffect(Unit) { hotkeys.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .focusRequester(hotkeys)
            .focusable()
            .onPreviewKeyEvent { event ->
                val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                val paste = down && event.isCtrlPressed && event.isShiftPressed &&
                    event.key == androidx.compose.ui.input.key.Key.V
                val grab = down && event.isCtrlPressed && event.isShiftPressed &&
                    event.key == androidx.compose.ui.input.key.Key.S
                if (paste) takeClipboard()
                if (grab) grabScreen()
                paste || grab
            },
    ) {

        signIn?.let { gate ->
            SignInPane(
                state = gate,
                onSignIn = account::signIn,
                onCancel = account::cancel,
                onOpenAgain = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
                onContinue = account::dismissGate,
            )
            return@Surface
        }
        Column(Modifier.fillMaxSize()) {

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

                LinkChip(lastContact, phoneSeen = circle.any { !it.self && (it.lastSeenMillis ?: 0L) > 0L })
                Spacer(Modifier.width(16.dp))

                message?.let { text ->
                    LaunchedEffect(text) {
                        kotlinx.coroutines.delay(20_000)
                        state.dismissMessage()
                    }
                    Text(
                        text,
                        style = PointType.small,
                        modifier = Modifier.clickable { state.dismissMessage() },
                    )
                }
                Spacer(Modifier.weight(1f))

                ConnectionChip(settings, onShowDevices = { showSettings = true })
            }
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(PointColors.border))
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {

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

            val remembered = remember(journal, items) {
                com.point.desktop.recentBesides(journal, items.map { it.obj.uri.value }.toSet())
            }
            if (items.isEmpty() && remembered.isEmpty()) {

                EmptyScreen(config, onTakeClipboard = takeClipboard, onGrabScreen = grabScreen) {
                    MyDevicesPane(
                        email = account.current()?.email.orEmpty(),
                        devices = circle,
                        busy = accountBusy,
                        error = accountError,
                        onRevoke = account::revoke,
                        onSignOut = { state.forgetEverything(onWipe); account.signOut() },
                    )
                }
            } else {

                var selectedId by remember { mutableStateOf<String?>(null) }
                val selected = items.firstOrNull { it.obj.id == selectedId } ?: items.firstOrNull()
                Row(Modifier.fillMaxSize()) {
                    Dock(
                        items = items,
                        selected = selected,
                        onSelect = { selectedId = it.obj.id },
                        recent = remembered,
                        onOpenAgain = { entry ->
                            state.openAgain(entry)?.let { selectedId = it.obj.id }
                        },
                        onTakeClipboard = takeClipboard,
                    )
                    Spacer(Modifier.width(1.dp).fillMaxHeight().background(PointColors.border))
                    Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                        if (selected == null) {

                            EmptyScreen(config, onTakeClipboard = takeClipboard, onGrabScreen = grabScreen) {
                                MyDevicesPane(
                                    email = account.current()?.email.orEmpty(),
                                    devices = circle,
                                    busy = accountBusy,
                                    error = accountError,
                                    onRevoke = account::revoke,
                                    onSignOut = account::signOut,
                                )
                            }
                        } else {
                            Conveyor(state, selected)
                        }
                    }
                }
            }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Point для ПК") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    MyDevicesPane(
                        email = account.current()?.email.orEmpty(),
                        devices = circle,
                        busy = accountBusy,
                        error = accountError,
                        onRevoke = account::revoke,
                        onSignOut = { state.forgetEverything(onWipe); account.signOut() },
                    )
                    SettingsScreen(
                        config = settings,
                        onSave = { changed -> settings = changed; onSaveSettings(changed) },
                        onSweepNow = onSweepNow,
                        onClose = { showSettings = false },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Готово") } },
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

@Composable
private fun LinkChip(lastContact: Long?, phoneSeen: Boolean = false) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val link = com.point.core.flow.linkStateOf(lastContact, now, knownButUnheard = phoneSeen)
    val dot = when (link) {
        is com.point.core.flow.LinkState.Live -> PointColors.cyan
        is com.point.core.flow.LinkState.Silent -> PointColors.violet

        com.point.core.flow.LinkState.Checking -> PointColors.violet
        com.point.core.flow.LinkState.Never -> PointColors.border
        com.point.core.flow.LinkState.Waiting -> PointColors.border
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Телефон · " + com.point.core.flow.linkLabel(link), style = PointType.small)
    }
}

@Composable
private fun ConnectionChip(config: PcConfig, onShowDevices: () -> Unit) {
    OutlinedButton(onClick = onShowDevices) {
        Text("● ${config.name}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            "Устройства",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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
