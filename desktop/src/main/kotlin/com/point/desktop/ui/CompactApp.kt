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
import com.point.core.flow.yieldLabel
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.CircleDevice
import com.point.core.ui.bubbleColor
import com.point.core.ui.kindLabel
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
    onImageDropped: (java.awt.image.BufferedImage) -> Unit = {},
    onClipboardTaken: (String) -> Unit = onTextDropped,

    /** Человек попросил окно не прятаться: пока просьба в силе, флайаут стоит. */

    onGrabScreen: (() -> File?)? = null,

    /** Приём файла по ссылке — на компьютере он тоже есть (#727). */
    onReceiveFile: () -> Unit = {},
    onCancelReceive: () -> Unit = {},

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

    // Принесли пачку файлов: они все стали объектами, но выбирает из них человек.
    var broughtBatch by remember { mutableStateOf(false) }

    // Прибывшее из списка раскрывается само; человека внутри другого объекта
    // не выдёргивают — ему предлагают (аудит компакта, раунд 2).
    var lastTop by remember { mutableStateOf(items.firstOrNull()?.obj?.id) }
    LaunchedEffect(items.firstOrNull()?.obj?.id) {
        val top = items.firstOrNull()?.obj?.id
        if (top != null && top != lastTop) {
            when {
                broughtBatch -> openedId = null
                else -> when (com.point.desktop.arrivalReaction(openedId)) {
                    com.point.desktop.ArrivalReaction.OPEN -> openedId = top
                    com.point.desktop.ArrivalReaction.INVITE -> invited = top
                }
            }
        }
        broughtBatch = false
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

    // Пока над окном тянут файл, флайаут не исчезает из-под руки (#546).
    var dragging by remember { mutableStateOf(false) }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { dragging = true }

            override fun onEnded(event: DragAndDropEvent) { dragging = false }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val brought = runCatching { com.point.desktop.readDropped(event.awtTransferable) }
                    .getOrElse { com.point.desktop.Dropped.NotTaken(com.point.desktop.DROP_UNREADABLE) }

                // Пачку Point не открывает за человека — она ложится списком.
                broughtBatch = com.point.desktop.droppedAsBatch(brought)
                if (broughtBatch) openedId = null
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

    val hotkeys = remember { FocusRequester() }
    LaunchedEffect(Unit) { hotkeys.requestFocus() }

    // Окно ведёт себя как окно: уход в другое приложение его не закрывает (владелец
    // 12.08.2026: «сделай десктопное окно нормальным, чтобы не пришлось жать кнопку не
    // закрывать»). Прежде это был флайаут — потерял фокус и исчез, — и человек, уходивший
    // за файлом, возвращался к пустому месту. Отсюда же взялась кнопка «Не прятать окно»:
    // костыль поверх поведения, которого человек не просил. Закрыть можно крестиком в шапке
    // и клавишей Escape.
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
                    onReceiveFile = onReceiveFile,
                    onCopyReceiveLink = { link -> state.copyFact(link) },
                    onCancelReceive = onCancelReceive,
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

    // Молчащий телефон — выбор человека, а не наш (#611, срез 5): исполнитель, который
    // ответит через час, не может быть выбран за спиной.
    val phoneAsk by state.phoneAsk.collectAsState()
    phoneAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { state.declinePhone() },
            title = { Text(ask.title) },
            text = { Text(ask.what) },
            confirmButton = { TextButton(onClick = { state.approvePhone() }) { Text("Подождать телефон") } },
            dismissButton = { TextButton(onClick = { state.declinePhone() }) { Text("Не сейчас") } },
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeekThumb(item)
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
            PeekHint(item)
        }
        HeaderButton("✕") { onDismiss() }
    }
}

/** Превью в плашке: картинке — миниатюра, тексту — первые слова, остальному — знак вида. */
@Composable
private fun PeekThumb(item: InboxItem) {
    val image = if (item.obj.state.kind == com.point.core.model.ObjectKind.IMAGE) {
        remember(item.obj.uri.value) {
            runCatching {
                java.io.File(item.obj.uri.value).inputStream().use {
                    androidx.compose.ui.res.loadImageBitmap(it)
                }
            }.getOrNull()
        }
    } else {
        null
    }
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier.size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PointColors.surfaceDeep),
            contentAlignment = Alignment.Center,
        ) {
            Text(kindMark(item.obj.state.kind), style = PointType.body.copy(color = PointColors.violet))
        }
    }
}

@Composable
private fun PeekHint(item: InboxItem) {
    if (item.obj.state.kind != com.point.core.model.ObjectKind.TEXT) return
    val line = remember(item.obj.uri.value) {
        runCatching {
            java.io.File(item.obj.uri.value).useLines { lines ->
                lines.firstOrNull { it.isNotBlank() }
            }
        }.getOrNull()?.trim()?.take(70)
    }
    if (!line.isNullOrBlank()) {
        Text(
            line,
            style = PointType.small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            HeaderGlyph()
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

/** Мини-знак Point — то же кольцо, что лончер телефона и трей. */
@Composable
private fun HeaderGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color(0xFFEAF0FF),
                0.45f to Color(0xFF9B7BFF),
                1f to Color(0xFF00A6FF),
            ),
            radius = r * 0.62f,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.34f),
        )
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
    // Шапка — рама окна, а не место объекта (#879): имя файла там отрывало идентичность
    // объекта от самого объекта. Объект называется у портала, ниже.
    CompactHeader(
        title = "Point",
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
        PortalPreview(item)

        // Вид крупно, имя тише, мера самым тихим — одна иерархия с телефоном (#879).
        // Раньше вид стоял подписью над порталом, а имя — в шапке окна, оторванное от
        // самого объекта.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                item.obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY]
                    ?: kindLabel(item.obj.state.kind),
                style = PointType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.obj.metadata["name"]?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = PointType.small.copy(color = PointColors.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Knowledge(
            item,
            onCopyFact = state::copyFact,
            questionName = { id -> state.questionName(id, item.obj.state) },
        )

        working?.let { Working(it) { state.cancelWork() } }

        val actions = state.actionsFor(item)
        if (actions.isNotEmpty()) {

            // Группы по смыслу — те же, что на телефоне (#879). Раньше здесь был один
            // список «Что можно сделать»: порядок совпадал с телефонным, но человеку это
            // было не видно. Действие без пузыря (просьба к телефону) идёт последней
            // группой — у него нет своего намерения, кроме «отправить».
            val primary = actions.indexOfFirst { it.unavailable == null }
            val grouped = com.point.core.ui.actionGroupOrder().mapNotNull { group ->
                actions.filter { choice ->
                    val intent = choice.bubble?.intent
                    if (intent == null) group == com.point.core.ui.ActionGroup.SEND
                    else com.point.core.ui.actionGroupOf(intent) == group
                }.takeIf { it.isNotEmpty() }?.let { group to it }
            }
            grouped.forEach { (group, rows) ->
                Text(group.label.uppercase(), style = PointType.label)
                rows.forEach { action ->
                val i = actions.indexOf(action)
                when {
                    action.unavailable != null -> MutedStation(
                        action.title,
                        where = if (action.onPhone) "на телефоне" else null,
                        reason = action.unavailable,
                        icon = action.icon,
                        appearIndex = i,
                    ) { state.say(action.unavailable) }

                    action.bubble != null -> Station(
                        action.title,
                        bubbleColor(action.icon),
                        primary = i == primary,
                        icon = action.icon,
                        note = yieldLabel(action.bubble.yields, action.bubble.unusableReason),
                        appearIndex = i,
                    ) { state.onBubble(item, action.bubble) }

                    action.remote != null -> Station(
                        action.title,
                        bubbleColor(action.icon),
                        where = "на телефоне",
                        primary = i == primary,
                        icon = action.icon,
                        appearIndex = i,
                    ) { state.sendToPhone(item, action.remote) }
                }
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
    onReceiveFile: () -> Unit = {},
    onCopyReceiveLink: (String) -> Unit = {},
    onCancelReceive: () -> Unit = {},
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

        // Приём файла есть и здесь (#727): «и на пк тоже и прием и отправка».
        val awaiting by state.receiving.collectAsState()
        if (awaiting == null) {
            Station("Принять файл по ссылке", bubbleColor("link"), icon = "link") { onReceiveFile() }
        } else {
            awaiting?.let { wait ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(PointColors.surfaceDeep, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Пусть отправят файл сюда", style = PointType.body)
                    Text(wait.link, style = PointType.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(wait.failed ?: wait.status, style = PointType.small)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderButton("⧉") { onCopyReceiveLink(wait.link) }
                        HeaderButton("×") { onCancelReceive() }
                    }
                }
            }
        }
        Station("Взять то, что в буфере", bubbleColor("copy"), icon = "copy") { onTakeClipboard() }
        Station("Снять экран целиком", bubbleColor("camera"), icon = "camera") { onGrabScreen() }
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
