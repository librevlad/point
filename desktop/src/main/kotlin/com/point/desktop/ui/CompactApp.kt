package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.PcConfig
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

/**
 * Компакт-окно Point для ПК — решение владельца 2026-08-09: «в него надо попробовать
 * уместить примерно то же, что показал бы мобильный Point». Один столбец: список ⇄
 * сцена объекта ⇄ настройки; язык и палитра — телефонные.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CompactApp(
    state: DesktopState,
    config: PcConfig,
    account: com.point.desktop.DesktopAccount,
    openObject: StateFlow<String?>,
    onObjectOpened: () -> Unit,
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},
    onClipboardTaken: (String) -> Unit = onTextDropped,

    onGrabScreen: (() -> File?)? = null,

    onWipe: () -> Unit = {},
    onSaveSettings: (PcConfig) -> Unit = {},
    onSweepNow: () -> Unit = {},
    onHide: () -> Unit = {},
) {
    val items by state.items.collectAsState()
    val message by state.message.collectAsState()
    val journal by state.journal.collectAsState()
    val signIn by account.signIn.collectAsState()
    val circle by account.circle.collectAsState()
    val accountBusy by account.busy.collectAsState()
    val accountError by account.error.collectAsState()
    var settings by remember { mutableStateOf(config) }
    var showSettings by remember { mutableStateOf(false) }

    var openedId by remember { mutableStateOf<String?>(null) }
    var invited by remember { mutableStateOf<String?>(null) }

    // Прибывшее из списка раскрывается само; человека внутри другого объекта
    // не выдёргивают — ему предлагают (аудит компакта, раунд 2).
    var lastTop by remember { mutableStateOf(items.firstOrNull()?.obj?.id) }
    LaunchedEffect(items.firstOrNull()?.obj?.id) {
        val top = items.firstOrNull()?.obj?.id
        if (top != null && top != lastTop) {
            when (com.point.desktop.arrivalReaction(openedId)) {
                com.point.desktop.ArrivalReaction.OPEN -> openedId = top
                com.point.desktop.ArrivalReaction.INVITE -> invited = top
            }
        }
        lastTop = top
    }

    // Открытый объект просмотрен: след «нового» снимается.
    LaunchedEffect(openedId) {
        openedId?.let { state.markSeen(it) }
        if (invited == openedId) invited = null
    }

    // Клик по peek-плашке открывает именно тот объект.
    val fromPeek by openObject.collectAsState()
    LaunchedEffect(fromPeek) {
        fromPeek?.let { id ->
            openedId = id
            onObjectOpened()
        }
    }

    val takeClipboard = {
        val text = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrBlank()) state.say("В буфере пусто") else onClipboardTaken(text)
    }
    val grabScreen = {
        val file = onGrabScreen?.invoke()
        if (file == null) state.say("Снять экран не вышло") else onFilesDropped(listOf(file))
    }

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

    val hotkeys = remember { FocusRequester() }
    LaunchedEffect(Unit) { hotkeys.requestFocus() }

    // Флайаут: потерял фокус — спрятался (alwaysOnTop честен, только пока окно нужно).
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val asking by state.cloudAsk.collectAsState()
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused && asking == null) {
            kotlinx.coroutines.delay(250)
            if (!windowInfo.isWindowFocused && state.cloudAsk.value == null) onHide()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
            .border(1.dp, PointColors.border, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .focusRequester(hotkeys)
            .focusable()
            .onPreviewKeyEvent { event ->
                val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                val paste = down && event.isCtrlPressed && event.isShiftPressed &&
                    event.key == androidx.compose.ui.input.key.Key.V
                val grab = down && event.isCtrlPressed && event.isShiftPressed &&
                    event.key == androidx.compose.ui.input.key.Key.S
                val esc = down && event.key == androidx.compose.ui.input.key.Key.Escape
                if (paste) takeClipboard()
                if (grab) grabScreen()
                if (esc) {
                    if (openedId != null) openedId = null else onHide()
                }
                paste || grab || esc
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

        val opened = items.firstOrNull { it.obj.id == openedId }
        Column(Modifier.fillMaxSize().background(PointColors.window)) {
            when {
                showSettings -> CompactSettings(
                    modifier = Modifier.weight(1f),
                    config = settings,
                    account = account,
                    circle = circle,
                    busy = accountBusy,
                    error = accountError,
                    onWipe = { state.forgetEverything(onWipe) },
                    onSave = { changed -> settings = changed; onSaveSettings(changed) },
                    onSweepNow = onSweepNow,
                    onBack = { showSettings = false },
                )

                opened != null -> CompactObject(
                    modifier = Modifier.weight(1f),
                    state = state,
                    item = opened,
                    invited = items.firstOrNull { it.obj.id == invited && it.obj.id != opened.obj.id },
                    onOpenInvited = { openedId = it.obj.id },
                    onBack = { openedId = null },
                )

                else -> CompactList(
                    modifier = Modifier.weight(1f),
                    state = state,
                    items = items,
                    onOpen = { openedId = it.obj.id },
                    onTakeClipboard = takeClipboard,
                    onGrabScreen = grabScreen,
                    onSettings = { showSettings = true },
                    onHide = onHide,
                )
            }

            message?.let { text ->
                LaunchedEffect(text) {
                    kotlinx.coroutines.delay(15_000)
                    state.dismissMessage()
                }
                Text(
                    text,
                    style = PointType.small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .background(PointColors.surfaceDeep)
                        .clickable { state.dismissMessage() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    // Согласие в момент выбора, словами последствий (P11).
    val cloudAsk by state.cloudAsk.collectAsState()
    cloudAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { state.declineCloud() },
            title = { Text(ask.title) },
            text = { Text(ask.destination) },
            confirmButton = { TextButton(onClick = { state.approveCloud() }) { Text(ask.confirm) } },
            dismissButton = { TextButton(onClick = { state.declineCloud() }) { Text("Не сейчас") } },
        )
    }
}

/** Плашка прибытия — своё окошко Point, не системное уведомление. Клик — открыть. */
@Composable
fun PeekCard(
    item: InboxItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    source: com.point.desktop.ObjectSource = com.point.desktop.ObjectSource.PHONE_RELAY,
) {
    Row(
        modifier = Modifier.fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PointColors.surface)
            .border(1.dp, PointColors.violet.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(PointColors.violet, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when (source) {
                    com.point.desktop.ObjectSource.LOCAL -> "ГОТОВО НА КОМПЬЮТЕРЕ"
                    else -> "ПРИШЛО С ТЕЛЕФОНА"
                },
                style = PointType.label,
            )
            Text(
                item.obj.metadata["name"] ?: "Объект",
                style = PointType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeaderButton("✕") { onDismiss() }
    }
}

@Composable
private fun CompactHeader(
    title: String,
    onBack: (() -> Unit)?,
    onHide: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp).background(PointColors.window)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            HeaderButton("←", onBack)
        } else {
            Box(
                Modifier.size(12.dp)
                    .border(2.dp, PointColors.violet, CircleShape),
            )
        }
        Text(
            title,
            style = PointType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        trailing?.invoke()
        onHide?.let { HeaderButton("✕", it) }
    }
}

@Composable
private fun HeaderButton(mark: String, onClick: () -> Unit) {
    Text(
        mark,
        style = PointType.body.copy(color = PointColors.muted),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Сцена объекта — то же, что показал бы мобильный Point: превью, знание, действия, путь. */
@Composable
internal fun CompactObject(
    state: DesktopState,
    item: InboxItem,
    onBack: () -> Unit,
    invited: InboxItem? = null,
    onOpenInvited: (InboxItem) -> Unit = {},
    modifier: Modifier = Modifier,
) = Column(modifier) {
    val journal by state.journal.collectAsState()
    val working by state.working.collectAsState()
    val now = rememberNow()
    CompactHeader(
        title = item.obj.metadata["name"] ?: "Объект",
        onBack = onBack,
        onHide = null,
    )

    // Пришло новое, пока человек здесь работает: не выдёргиваем — приглашаем.
    invited?.let { fresh ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(PointColors.surfaceDeep)
                .clickable { onOpenInvited(fresh) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(PointColors.cyan, CircleShape))
            Text(
                "Пришло: " + (fresh.obj.metadata["name"] ?: "объект"),
                style = PointType.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("открыть →", style = PointType.small.copy(color = PointColors.cyan))
        }
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item.obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY]?.let {
            Text(it, style = PointType.small)
        } ?: Text(kindLabel(item.obj.state.kind), style = PointType.small)

        PortalPreview(item)
        Knowledge(
            item,
            onCopyFact = state::copyFact,
            questionName = { id -> state.questionName(id, item.obj.state) },
        )

        working?.let { Working(it) { state.cancelWork() } }

        val actions = state.actionsFor(item)
        if (actions.isNotEmpty()) {
            Text("ЧТО МОЖНО СДЕЛАТЬ", style = PointType.label)
            val primary = actions.indexOfFirst { it.unavailable == null }
            actions.forEachIndexed { i, action ->
                when {
                    action.unavailable != null -> MutedStation(
                        action.title,
                        where = if (action.onPhone) "на телефоне" else null,
                        reason = action.unavailable,
                    ) { state.say(action.unavailable) }

                    action.bubble != null -> Station(
                        action.title,
                        PointColors.violet,
                        primary = i == primary,
                    ) { state.onBubble(item, action.bubble) }

                    action.remote != null -> Station(
                        action.title,
                        PointColors.violet,
                        where = "на телефоне",
                        primary = i == primary,
                    ) { state.sendToPhone(item, action.remote) }
                }
            }
        }

        FoldedPath(journal.firstOrNull { it.path == item.obj.uri.value }, now)
    }
}

/** Список: «сейчас» + «было раньше» + двери входа. */
@Composable
internal fun CompactList(
    state: DesktopState,
    items: List<InboxItem>,
    onOpen: (InboxItem) -> Unit,
    onTakeClipboard: () -> Unit,
    onGrabScreen: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    val journal by state.journal.collectAsState()
    val lastContact by state.lastContact.collectAsState()
    val fresh by state.fresh.collectAsState()
    val working by state.working.collectAsState()
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    CompactHeader(
        title = "Point",
        onBack = null,
        onHide = onHide,
        trailing = { HeaderButton("⚙", onSettings) },
    )

    // Работа идёт — видно и из списка, с дорогой обратно к объекту.
    working?.let { work ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(PointColors.surfaceDeep)
                .clickable {
                    work.objectId?.let { id ->
                        state.items.value.firstOrNull { it.obj.id == id }?.let(onOpen)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(PointColors.cyan, CircleShape))
            Text(
                work.title + (work.stage?.let { " · $it" } ?: ""),
                style = PointType.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val link = com.point.core.flow.linkStateOf(lastContact, now, knownButUnheard = true)
        Text(
            "Телефон · " + com.point.core.flow.linkLabel(link),
            style = PointType.small,
        )

        if (items.isNotEmpty()) {
            Text("СЕЙЧАС", style = PointType.label, modifier = Modifier.padding(top = 4.dp))
            items.forEach { item ->
                ListRow(
                    name = item.obj.metadata["name"] ?: "Объект",
                    note = kindLabel(item.obj.state.kind),
                    accent = true,
                    fresh = item.obj.id in fresh,
                ) { onOpen(item) }
            }
        }

        val remembered = remember(journal, items) {
            com.point.desktop.recentBesides(journal, items.map { it.obj.uri.value }.toSet())
        }
        if (remembered.isNotEmpty()) {
            Text("ИСТОРИЯ", style = PointType.label, modifier = Modifier.padding(top = 4.dp))
            remembered.forEach { entry ->
                ListRow(
                    name = entry.name.ifBlank { "Объект" },
                    note = com.point.desktop.sourceShort(entry.source) + " · " +
                        com.point.desktop.whenLabel(entry.at, now, zone),
                    accent = false,
                ) { state.openAgain(entry)?.let(onOpen) }
            }
        }

        if (items.isEmpty() && remembered.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Point ждёт объект.\nПоделитесь с телефона, бросьте файл сюда или возьмите буфер.",
                style = PointType.small,
            )
        }

        Spacer(Modifier.height(4.dp))
        Station("Взять то, что в буфере", PointColors.cyan) { onTakeClipboard() }
        Station("Снять экран целиком", PointColors.violet) { onGrabScreen() }
    }
}

@Composable
private fun ListRow(
    name: String,
    note: String,
    accent: Boolean,
    fresh: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) PointColors.surface else PointColors.surfaceDeep.copy(alpha = 0.6f))
            .border(1.dp, PointColors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp)
                .background(if (accent) PointColors.violet else PointColors.muted, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = PointType.body.copy(fontSize = PointType.small.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = PointType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fresh) Text("новое", style = PointType.small.copy(color = PointColors.cyan))
    }
}

@Composable
private fun CompactSettings(
    config: PcConfig,
    account: com.point.desktop.DesktopAccount,
    circle: List<CircleDevice>,
    busy: Boolean,
    error: String?,
    onWipe: () -> Unit,
    onSave: (PcConfig) -> Unit,
    onSweepNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    CompactHeader(title = "Настройки", onBack = onBack, onHide = onBack)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MyDevicesPane(
            email = account.current()?.email.orEmpty(),
            devices = circle,
            busy = busy,
            error = error,
            onRevoke = account::revoke,
            onSignOut = { onWipe(); account.signOut() },
        )
        SettingsScreen(
            config = config,
            onSave = onSave,
            onSweepNow = onSweepNow,
            onClose = onBack,
        )
    }
}
