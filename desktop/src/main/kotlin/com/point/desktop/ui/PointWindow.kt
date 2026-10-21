package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.point.core.flow.ClipboardPayload
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.PcConfig
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import com.point.core.ui.GlassIcons
import com.point.core.ui.PointGlyph

/**
 * Окно Point на компьютере (макет 19.10.2026): шапка с поиском, слева «Новый объект» и
 * «Недавнее», справа открытый объект или настройки.
 *
 * В узком окне панель одна — список ⇄ объект ⇄ настройки, как в прежнем компакте: две панели
 * в 475 точках не помещаются, и правая сжималась бы до нечитаемой.
 *
 * Окно ведёт себя как окно: уход в другое приложение его не закрывает (владелец 12.08.2026).
 * Закрыть можно крестиком в шапке и клавишей Escape.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PointWindow(
    state: DesktopState,
    config: PcConfig,
    account: com.point.desktop.DesktopAccount,
    openObject: StateFlow<String?>,
    onObjectOpened: () -> Unit,
    chrome: WindowChrome = WindowChrome(),
    onFilesDropped: (List<File>) -> Unit = {},
    onTextDropped: (String) -> Unit = {},
    onImageDropped: (java.awt.image.BufferedImage) -> Unit = {},
    onClipboardTaken: (ClipboardPayload) -> Unit = { onTextDropped(it.text()) },

    /** Выбрать файл на диске — главная дверь «Нового объекта» (Ctrl+O). */
    onPickFile: () -> Unit = {},

    /** Снять экран и сделать снимок объектом; ответ — файл снимка, `null` — снять не вышло. */
    onGrabScreen: (suspend () -> File?)? = null,

    /** Приём файла по ссылке — на компьютере он тоже есть (#727). */
    onReceiveFile: () -> Unit = {},
    onCancelReceive: () -> Unit = {},

    onWipe: () -> Long = { 0L },
    onSaveSettings: (PcConfig) -> Unit = {},

    /**
     * Выключатель меню файла Windows (#1082): поставить или снять пункт и ответить по эффекту —
     * встал ли; `null` — прочитать, что вышло, не удалось. Это другое действие, чем сохранить
     * настройку: флаг на диске и пункт в реестре — не одно и то же, и правда о пункте живёт
     * в реестре.
     */
    onRightClick: suspend (Boolean) -> Boolean? = { true },

    /**
     * Стоит ли меню файла Windows в этом положении на деле — читается при показе настроек, а
     * не помнится с последнего тапа (#1082): после перезапуска переключатель говорит правду.
     */
    rightClickHolds: suspend (Boolean) -> Boolean? = { true },
    onSweepNow: () -> Int = { 0 },
    onHide: () -> Unit = {},
) {
    val items by state.items.collectAsState()
    val journal by state.journal.collectAsState()
    val signedInAs by account.account.collectAsState()
    val signIn by account.signIn.collectAsState()
    val circle by account.circle.collectAsState()
    val accountBusy by account.busy.collectAsState()
    val accountError by account.error.collectAsState()
    var settings by remember { mutableStateOf(config) }
    var showSettings by remember { mutableStateOf(false) }

    // Раздел настроек живёт здесь, а не внутри настроек: Esc обязан вести себя как «←»
    // текущего экрана, а для этого корню окна нужно знать, где человек стоит (#1025).
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }

    var openedId by remember { mutableStateOf<String?>(null) }
    var invited by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // Принесли пачку файлов: они все стали объектами, но выбирает из них человек.
    var broughtBatch by remember { mutableStateOf(false) }

    // Прибывшее раскрывается само; человека внутри другого объекта не выдёргивают — ему
    // предлагают (аудит компакта, раунд 2).
    //
    // До первой сборки экрана человек не видел ещё ничего (#1019, DSK-001): объект, принятый
    // раньше, чем экран собрался, — запуск с файлом — такое же прибытие, а не «уже виденный
    // верх списка».
    var lastTop by remember { mutableStateOf<String?>(null) }
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

    // Клик по плашке прибытия или по строке панели у трея открывает именно тот объект.
    val fromPeek by openObject.collectAsState()
    LaunchedEffect(fromPeek) {
        fromPeek?.let { id ->
            openedId = id
            showSettings = false
            onObjectOpened()
        }
    }

    val scope = rememberCoroutineScope()
    val ways = remember(state, scope) {
        newObjectWays(state, scope, onPickFile, onClipboardTaken, onGrabScreen, onReceiveFile, onCancelReceive)
    }
    val dropTarget = rememberDropTarget(state, onFilesDropped, onTextDropped, onImageDropped) { batch ->
        broughtBatch = batch
        if (batch) openedId = null
    }
    val open: (InboxItem) -> Unit = { openedId = it.obj.id; showSettings = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANES
        val searchFits = maxWidth >= SEARCH_FITS
        val opened = items.firstOrNull { it.obj.id == openedId }
        GlassWindow(
            Modifier.fillMaxSize()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)

                // Клавиши окна — в `windowKeys`: корень слышит их сам, но не выдёргивает
                // курсор из поля, в которое человек печатает (#1025).
                .windowKeys { event ->
                    newObjectKey(event, ways) || (escape(event) && run {
                        val step = com.point.desktop.escapeStep(
                            settingsOpen = showSettings,
                            settingsAtRoot = settingsPage == SettingsPage.ROOT,
                            objectOpen = openedId != null,
                        )
                        when (step) {
                            com.point.desktop.EscapeStep.SETTINGS_SECTION_BACK -> settingsPage = SettingsPage.ROOT
                            com.point.desktop.EscapeStep.SETTINGS_CLOSE -> showSettings = false
                            com.point.desktop.EscapeStep.OBJECT_CLOSE -> openedId = null
                            com.point.desktop.EscapeStep.WINDOW_HIDE -> onHide()
                        }
                        true
                    })
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                WindowHeader(
                    state = state,
                    chrome = chrome,
                    query = query,
                    onQuery = { query = it },
                    // Пока идёт вход, искать и настраивать нечего: поиск и шестерёнка на экране
                    // входа были бы кнопками, которые ничего не делают.
                    search = wide && searchFits && signIn == null,
                    onBack = if (!wide && opened != null && !showSettings && signIn == null) ({ openedId = null }) else null,
                    settings = if (signIn != null) {
                        null
                    } else {
                        {
                            // Знак настроек законен только здесь, в шапке окна рядом с крестиком
                            // (#1318): там, где выбирают действие над объектом, безымянных знаков нет.
                            RoundButton(GlassIcons.Gear, com.point.core.flow.SETTINGS_TITLE, {
                                // Вход в настройки всегда с корня, как и раньше.
                                settingsPage = SettingsPage.ROOT
                                showSettings = !showSettings
                            })
                        }
                    },
                    onHide = onHide,
                    account = account,
                    onDevices = if (signIn != null) {
                        null
                    } else {
                        {
                            settingsPage = SettingsPage.DEVICES
                            showSettings = true
                        }
                    },
                )

                val gate = signIn
                val settingsPane: @Composable (Modifier) -> Unit = { modifier ->
                    SettingsPane(
                        modifier = modifier,
                        state = state,
                        page = settingsPage,
                        onPage = { settingsPage = it },
                        config = settings,
                        account = account,
                        circle = circle,
                        busy = accountBusy,
                        error = accountError,
                        onWipe = { state.forgetEverything(onWipe) },
                        onSave = { changed -> settings = changed; onSaveSettings(changed) },
                        onRightClick = onRightClick,
                        rightClickHolds = rightClickHolds,
                        onSweepNow = onSweepNow,
                        onBack = { showSettings = false },
                    )
                }
                val objectPane: @Composable (InboxItem, Modifier) -> Unit = { item, modifier ->
                    ObjectPane(
                        modifier = modifier,
                        state = state,
                        item = item,
                        invited = items.firstOrNull { it.obj.id == invited && it.obj.id != item.obj.id },
                        onOpenInvited = open,
                    )
                }
                when {
                    gate != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        SignInPane(
                            state = gate,
                            onSignIn = account::signIn,
                            onCancel = account::cancel,
                            // Та же дверь в браузер, что у действий (#1087). Вход в аккаунт браузером
                            // не держится: код и адрес человек видит на этом же экране, поэтому отказ
                            // двери его не обрывает.
                            onOpenAgain = { url -> runCatching { com.point.desktop.SystemBrowser().open(url) } },
                            onContinue = account::dismissGate,
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxHeight(),
                        )
                    }

                    wide -> Row(Modifier.weight(1f).fillMaxWidth()) {
                        RecentPane(
                            state = state,
                            items = items,
                            onOpen = open,
                            ways = ways,
                            linked = signedInAs != null,
                            selectedId = openedId.takeUnless { showSettings },
                            query = query,
                            modifier = Modifier.width(LIST_WIDTH).fillMaxHeight()
                                .drawBehind {
                                    drawLine(PointColors.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                                }
                                .padding(start = 10.dp, end = 10.dp, top = 12.dp),
                        )
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            when {
                                showSettings -> settingsPane(Modifier.fillMaxSize())
                                opened != null -> objectPane(opened, Modifier.fillMaxSize())
                                else -> NothingOpen(empty = items.isEmpty() && journal.isEmpty())
                            }
                        }
                    }

                    showSettings -> settingsPane(Modifier.weight(1f).fillMaxWidth())
                    opened != null -> objectPane(opened, Modifier.weight(1f).fillMaxWidth())
                    else -> RecentPane(
                        state = state,
                        items = items,
                        onOpen = open,
                        ways = ways,
                        linked = signedInAs != null,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp),
                    )
                }
            }
            WindowMessage(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            if (!chrome.maximized) chrome.resizeGrip?.invoke(Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }
    WindowDialogs(state)
}

/** С какой ширины окна список и объект стоят рядом. */
private val TWO_PANES = 760.dp

/** С какой ширины в шапке есть место поиску между именем окна и его кнопками. */
private val SEARCH_FITS = 900.dp

/** Ширина левой панели — по макету. */
private val LIST_WIDTH = 340.dp

/**
 * Шапка окна: знак, имя, связь с телефоном; поиск по «Недавнему»; настройки и кнопки окна.
 * За неё окно тянут — своей рамки у окна нет.
 */
@Composable
private fun WindowHeader(
    state: DesktopState,
    chrome: WindowChrome,
    query: String,
    onQuery: (String) -> Unit,
    search: Boolean,
    onBack: (() -> Unit)?,

    /** Дверь в настройки — её кладёт вызывающий; `null` — дверей нет, пока идёт вход. */
    settings: (@Composable () -> Unit)?,
    onHide: () -> Unit,
    account: com.point.desktop.DesktopAccount? = null,

    /** Куда ведёт строка связи — к «Моим устройствам», где их подключают. */
    onDevices: (() -> Unit)? = null,
) {
    chrome.dragArea(Modifier.fillMaxWidth().height(46.dp)) {
        Row(
            Modifier.fillMaxSize()
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.04f))
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                }
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) RoundButton(GlassIcons.ChevronLeft, "Назад", onBack) else PointGlyph()
                Text("Point", style = PointType.header)
                PhoneLink(state, account, Modifier.padding(start = 4.dp).weight(1f, fill = false), onClick = onDevices)
            }
            if (search) SearchField(query, onQuery, "Найти в недавнем", Modifier.width(320.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                settings?.invoke()
                chrome.onMinimize?.let { RoundButton(GlassIcons.Minimize, "Свернуть", it) }
                chrome.onToggleMaximize?.let {
                    RoundButton(
                        if (chrome.maximized) GlassIcons.Restore else GlassIcons.Maximize,
                        if (chrome.maximized) "Восстановить" else "Развернуть",
                        it,
                    )
                }
                RoundButton(GlassIcons.Close, "Закрыть", onHide, warm = true)
            }
        }
    }
}

/**
 * Связь с устройствами — в шапке, точкой и словами: «Pixel 8 · на связи». Прежде это была
 * первая строка списка, и она уезжала вместе с ним.
 *
 * Без входа устройства не подключены (решение владельца 20.10.2026), и строка говорит именно
 * это, а не «телефон · ждёт связи»: ждать телефона, который не может прийти, — неправда.
 * [onClick] ведёт туда, где их подключают.
 */
@Composable
internal fun PhoneLink(
    state: DesktopState,
    account: com.point.desktop.DesktopAccount?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val lastContact by state.lastContact.collectAsState()

    // Без аккаунта под рукой (тест, превью) строка не выдумывает «не подключены».
    val signedInAs = account?.account?.collectAsState()?.value
    val signedIn = account == null || signedInAs != null
    val circle = account?.circle?.collectAsState()?.value.orEmpty()
    val now = rememberNow()
    val link = com.point.core.flow.linkStateOf(lastContact, now, knownButUnheard = true)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.handCursor().clickable(onClick = onClick) else it }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(
            when {
                !signedIn -> PointColors.faint
                link is com.point.core.flow.LinkState.Live -> PointColors.green
                link == com.point.core.flow.LinkState.Checking -> PointColors.cyan
                else -> PointColors.faint
            },
        )
        Text(
            linkLine(signedIn, circle.filterNot { it.self }, link),
            style = PointType.small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Строка связи словами. Устройство одно — оно названо своим именем; их несколько или круг ещё не
 * прочитан — «устройства»: у человека бывают и два телефона, и планшет, и второй компьютер, а
 * слово «телефон» называло бы одно из них.
 */
internal fun linkLine(signedIn: Boolean, others: List<com.point.core.flow.CircleDevice>, link: com.point.core.flow.LinkState): String = when {
    !signedIn -> DEVICES_NOT_LINKED
    others.size == 1 -> others.single().name.ifBlank { "устройство" } + " · " + com.point.core.flow.linkLabel(link)
    else -> "устройства · " + com.point.core.flow.linkLabel(link)
}

/** Строка связи без входа: подключать устройства — дело входа, а не ожидания. */
internal const val DEVICES_NOT_LINKED = "устройства не подключены"

/**
 * Справа пусто, пока объект не выбран: что сюда встанет. Как его принести, сказано слева, под
 * «Новым объектом», — второй раз здесь это был бы второй вход в то же действие. Поэтому здесь
 * не кнопки, а подсказки клавишами: их не видно нигде, кроме меню, а нажимают их отсюда же.
 *
 * Прежде здесь стояли знак и жирная строка посреди пустоты — владелец 21.10.2026: «смотрится
 * топорно». Теперь знак светится порталом, как в иконке, заголовок спокойнее, а под ним
 * сказано, зачем объект открывать.
 */
@Composable
private fun NothingOpen(empty: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(140.dp).drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to PointColors.violet.copy(alpha = 0.22f),
                        0.55f to PointColors.blue.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                    ),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            PointGlyph(size = 56.dp)
        }
        Text(
            if (empty) "Здесь откроется объект" else "Выберите объект слева",
            style = PointType.title.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        )
        Text(
            "Point покажет, что в нём, и подскажет, что с ним сделать",
            style = PointType.small,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 320.dp),
        )
        Row(
            Modifier.padding(top = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyHint("Ctrl", "V", "из буфера")
            KeyHint("Ctrl", "O", "файл")
            KeyHint("Ctrl", "Shift", "S", "снимок")
        }
    }
}

/** Подсказка клавишами: клавиши колпачками, за ними — что они принесут. */
@Composable
private fun KeyHint(vararg parts: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.dropLast(1).forEach { key ->
            Text(
                key,
                style = PointType.note.copy(color = PointColors.soft, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(parts.last(), style = PointType.note, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Сообщение окна: капсула внизу, гаснет сама по сроку или по щелчку. */
@Composable
internal fun WindowMessage(state: DesktopState, modifier: Modifier = Modifier) {
    val message by state.message.collectAsState()
    message?.let { text ->
        LaunchedEffect(text) {
            // Срок жизни плашки назван один раз рядом со сроком досмотра стука (#1108): правда про
            // телефон обязана успеть встать на место строки «ждёт телефона», пока та ещё на экране.
            kotlinx.coroutines.delay(com.point.desktop.MESSAGE_LIVES_MS)
            state.dismissMessage()
        }
        MessageBar(text, onDismiss = { state.dismissMessage() }, modifier = modifier.widthIn(max = 560.dp))
    }
}

/** Согласие в момент выбора, словами последствий (P11), и выбор ждать ли молчащий телефон. */
@Composable
internal fun WindowDialogs(state: DesktopState) {
    val cloudAsk by state.cloudAsk.collectAsState()
    cloudAsk?.let { ask ->
        GlassDialog(
            title = ask.title,
            text = ask.destination,
            confirm = ask.confirm,
            onConfirm = { state.approveCloud() },
            onDismiss = { state.declineCloud() },
        )
    }

    // Молчащий телефон — выбор человека, а не наш (#611, срез 5): исполнитель, который
    // ответит через час, не может быть выбран за спиной.
    val phoneAsk by state.phoneAsk.collectAsState()
    phoneAsk?.let { ask ->
        GlassDialog(
            title = ask.title,
            text = ask.what,
            confirm = "Подождать телефон",
            onConfirm = { state.approvePhone() },
            onDismiss = { state.declinePhone() },
        )
    }
}

private fun escape(event: KeyEvent): Boolean = event.type == KeyEventType.KeyDown && event.key == Key.Escape
