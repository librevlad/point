package com.point.desktop.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
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

/**
 * Одно окно: док прилетевшего слева, выбранный объект и его действия справа, круг устройств на
 * месте прежней карточки с QR. Связывать больше нечего и нечем (#475).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DesktopApp(
    state: DesktopState,
    config: PcConfig,
    /** Аккаунт этого компьютера (#473): вход и круг устройств вместо пейринга. */
    account: com.point.desktop.DesktopAccount,
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},
    /** Взятое из буфера — отдельный вход: журнал (#407) должен отличать его от перетаскивания. */
    onClipboardTaken: (String) -> Unit = onTextDropped,
    /**
     * Снимок экрана как объект (#585) — четвёртый вход, которого нет и не будет у телефона.
     *
     * `null` — снять не вышло (среда без экрана): зовущий говорит об этом человеку словами.
     */
    onGrabScreen: (() -> File?)? = null,
) {
    val items by state.items.collectAsState()
    val message by state.message.collectAsState()
    val clipboardText by state.clipboardText.collectAsState()
    val lastContact by state.lastContact.collectAsState()
    val journal by state.journal.collectAsState()

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

    var showDevices by remember { mutableStateOf(false) }
    val signIn by account.signIn.collectAsState()
    val circle by account.circle.collectAsState()
    val accountBusy by account.busy.collectAsState()
    val accountError by account.error.collectAsState()
    // Круг обновляется при открытии экрана и после входа — тем же правилом «не в каждом шаге»,
    // что действует для `/caps` (#80).
    LaunchedEffect(signIn) { if (signIn == null) account.refreshCircle() }

    // «Взять то, что в буфере» (#285): подпись на экране обещает Ctrl+Shift+V, значит обещание
    // обязано работать. Хоткей действует, пока окно Point в фокусе; общесистемный — отдельная
    // работа (см. issue про глобальный хоткей).
    val takeClipboard = {
        val text = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrBlank()) state.say("В буфере пусто") else onClipboardTaken(text)
    }
    // Снять экран (#585). Окно Point прячется на миг перед съёмкой: иначе человек снимет сам
    // Point вместо того, что за ним, — и получит объект, которого не просил.
    val grabScreen = {
        val file = onGrabScreen?.invoke()
        if (file == null) state.say("Снять экран не вышло") else onFilesDropped(listOf(file))
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
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
        // Вход стоит перед работой (#473): компьютер без круга и раньше ничего не делал — он
        // стартовал карточкой «подключите телефон». Вход занял её место.
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
                // Связь названа вслух (#412): человек тапал «Напечатать на ПК» и не понимал,
                // сломалось оно или телефон просто не на связи. Строка живая — пересчитывается,
                // пока экран открыт, иначе «на связи» застыло бы на весь вечер.
                LinkChip(lastContact)
                Spacer(Modifier.width(16.dp))
                message?.let { Text(it, style = PointType.small) }
                Spacer(Modifier.weight(1f))
                ConnectionChip(config, onShowDevices = { showDevices = true })
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
            // Док показывается и тогда, когда в эту сессию ещё ничего не прилетело, но компьютер
            // помнит прежнее (#407): иначе после перезапуска вся память была бы не видна вовсе —
            // ровно та беда, ради которой срез и делался.
            val remembered = remember(journal, items) {
                com.point.desktop.recentBesides(journal, items.map { it.obj.uri.value }.toSet())
            }
            if (items.isEmpty() && remembered.isEmpty()) {
                // Экран без объекта занят тем, чем работу начать (#285): портал, три способа
                // дать объект и подключение телефона — а не одним лишь QR.
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
                // Конвейер (#285, подход 2a): док прилетевшего слева, объект и его действия —
                // справа. Сетка карточек ушла: она показывала много объектов сразу и ни одного
                // как следует, а работа в Point всегда идёт с одним.
                var selectedId by remember { mutableStateOf<String?>(null) }
                val selected = items.firstOrNull { it.obj.id == selectedId } ?: items.firstOrNull()
                Row(Modifier.fillMaxSize()) {
                    Dock(
                        items = items,
                        selected = selected,
                        onSelect = { selectedId = it.obj.id },
                        recent = remembered,
                        onOpenAgain = { entry -> state.openAgain(entry) },
                    )
                    Spacer(Modifier.width(1.dp).fillMaxHeight().background(PointColors.border))
                    Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                        if (selected == null) {
                            // Память есть, объекта на экране нет: место занято тем, чем начать
                            // работу, а не пустотой рядом со списком.
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

    if (showDevices) {
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text(com.point.core.flow.MY_DEVICES_TITLE) },
            text = {
                MyDevicesPane(
                    email = account.current()?.email.orEmpty(),
                    devices = circle,
                    busy = accountBusy,
                    error = accountError,
                    onRevoke = account::revoke,
                    onSignOut = account::signOut,
                )
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Готово") } },
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

/**
 * Состояние связи с телефоном: точка-светофор и человеческие слова (#412).
 *
 * Пересчитывается раз в секунду, потому что «молчит 3 минуты» — величина, которая меняется сама:
 * замерший текст врал бы ровно в тот момент, когда человек на него смотрит.
 */
@Composable
private fun LinkChip(lastContact: Long?) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val link = com.point.core.flow.linkStateOf(lastContact, now)
    val dot = when (link) {
        is com.point.core.flow.LinkState.Live -> PointColors.cyan
        is com.point.core.flow.LinkState.Silent -> PointColors.violet
        // Компьютер сам никого не спрашивает — он ждёт, когда придут к нему, поэтому «проверяю»
        // здесь не возникает по построению. Цвет у него тот же, что у молчания: ответа нет.
        com.point.core.flow.LinkState.Checking -> PointColors.violet
        com.point.core.flow.LinkState.Never -> PointColors.border
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
