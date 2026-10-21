package com.point.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.ui.kindLabel
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import java.time.ZoneId
import com.point.core.ui.GlassIcons
import com.point.core.ui.kindIcon
import com.point.core.ui.kindTint

/**
 * «Недавнее» и способы взять новый объект — левая панель окна и выпадающая панель у трея (#836,
 * макет 19.10.2026).
 */

/**
 * Строка списка «Недавнее». Живой объект и запись журнала попадают в один поток и режутся
 * на секции времени вместе — иначе одна и та же «СЕГОДНЯ» встречается в списке дважды (#884).
 */
internal sealed interface RecentLine {
    val at: Long

    data class Live(
        val item: InboxItem,
        val source: com.point.desktop.ObjectSource? = null,
    ) : RecentLine {
        override val at: Long get() = item.receivedAt
    }

    data class Kept(val entry: com.point.desktop.JournalEntry) : RecentLine {
        override val at: Long get() = entry.at
    }
}

/**
 * Два источника — один поток, от свежего к старому.
 *
 * Живой объект берёт своё происхождение из журнала: та же запись, просто ещё открытая.
 * Пока он его не брал, вторая строка означала в одном списке разное — у одних «Документ»,
 * у других «с телефона», — и человек не мог понять, вид это или место (#884).
 */
internal fun recentLines(
    items: List<InboxItem>,
    remembered: List<com.point.desktop.JournalEntry>,
    journal: List<com.point.desktop.JournalEntry> = emptyList(),
): List<RecentLine> {
    val sourceOf = journal.associate { it.path to it.source }
    return (
        items.map { RecentLine.Live(it, sourceOf[it.obj.uri.value]) } +
            remembered.map { RecentLine.Kept(it) }
        ).sortedByDescending { it.at }
}

/**
 * Сколько строк «Недавнего» видно сразу, до просьбы показать ещё (#1098).
 *
 * Это страница, а не предел памяти: за ней стоит весь журнал, и «показать ещё» доводит до
 * последней записи, которую Point помнит.
 */
internal const val RECENT_PAGE = 12

/** Вторая строка одна на весь список: что это, и — если известно — откуда пришло. */
internal fun recentNote(kind: ObjectKind, source: com.point.desktop.ObjectSource?): String =
    listOfNotNull(kindLabel(kind), source?.let { com.point.desktop.sourceShort(it) })
        .joinToString(" · ")

internal fun RecentLine.name(): String = when (this) {
    is RecentLine.Live -> item.obj.metadata["name"]?.takeIf { it.isNotBlank() } ?: "Объект"
    is RecentLine.Kept -> entry.name.ifBlank { "Объект" }
}

internal fun RecentLine.kind(): ObjectKind = when (this) {
    is RecentLine.Live -> item.obj.state.kind
    is RecentLine.Kept -> runCatching { ObjectKind.valueOf(entry.kind) }.getOrDefault(ObjectKind.UNKNOWN)
}

private fun RecentLine.note(): String = when (this) {
    is RecentLine.Live -> recentNote(kind(), source)
    is RecentLine.Kept -> recentNote(kind(), entry.source)
}

/**
 * Поиск окна по «Недавнему»: имя и вторая строка, без учёта регистра (макет 19.10.2026 — «Найти
 * объект или действие»). Пустой запрос показывает всё.
 */
internal fun recentMatches(line: RecentLine, query: String): Boolean =
    matchesQuery(line.name(), query) || matchesQuery(line.note(), query)

/** Совпадение строки с поиском окна: каждое слово запроса встречается в тексте. */
internal fun matchesQuery(text: String, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = text.lowercase()
    return words.all { it in haystack }
}

/** Способы взять новый объект: им даёт дорогу раздельная кнопка «Новый объект». */
class NewObjectWays(
    val pickFile: () -> Unit,
    val takeClipboard: () -> Unit,
    val grabScreen: () -> Unit,
    val receiveFile: () -> Unit,
    val copyReceiveLink: (String) -> Unit = {},
    val cancelReceive: () -> Unit = {},

    /** Что лежит в буфере — словами, пока меню открыто: «текст, 2 строки», «картинка». */
    val clipboardNote: () -> String? = { null },
)

/**
 * Раздельная кнопка «Новый объект» с меню остальных способов.
 *
 * Вход один: файл выбирает сама кнопка, и в меню его второй раз нет — там только то, чего кнопка
 * не делает. Подсказка «бросьте файл» тоже живёт в одном месте, под кнопкой ([RecentPane]):
 * повторённая в меню, в пустом списке и в пустой правой панели, она читалась как четыре
 * разных входа в одно и то же действие.
 */
@Composable
internal fun NewObjectButton(ways: NewObjectWays, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val clip = remember(open) { if (open) ways.clipboardNote() else null }
    SplitButton(
        title = "Новый объект",
        note = "выбрать файл · Ctrl+O",
        onMain = ways.pickFile,
        menuOpen = open,
        onMenu = { open = it },
        items = newObjectMenu(ways, clip),
        modifier = modifier,
    )
}

/** Меню «Нового объекта» — только то, чего не делает сама кнопка; [clip] — что лежит в буфере. */
internal fun newObjectMenu(ways: NewObjectWays, clip: String?): List<MenuItem> = listOf(
    MenuItem("Из буфера", GlassIcons.Clipboard, ways.takeClipboard, note = clip, keys = "Ctrl+V"),
    MenuItem("Снимок экрана", GlassIcons.Screen, ways.grabScreen, keys = "Ctrl+Shift+S"),
    MenuItem("Принять по ссылке", GlassIcons.Link, ways.receiveFile),
)

/**
 * Левая панель окна: «Новый объект», идущая работа, ожидание файла по ссылке и «Недавнее».
 *
 * [selectedId] — объект, открытый справа: его строка подсвечена, чтобы человек видел, где стоит.
 */
@Composable
internal fun RecentPane(
    state: DesktopState,
    items: List<InboxItem>,
    onOpen: (InboxItem) -> Unit,
    ways: NewObjectWays,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    query: String = "",

    /** Связаны ли устройства: без входа звать «поделиться с телефона» нечестно. */
    linked: Boolean = true,
) = Column(modifier) {
    NewObjectButton(ways)
    Text(
        howToBring(linked),
        style = PointType.note,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
    )

    WorkingLine(state, onOpen)
    ReceiveWaiting(state, ways)

    RecentList(
        state = state,
        items = items,
        onOpen = onOpen,
        selectedId = selectedId,
        query = query,
        linked = linked,
        modifier = Modifier.weight(1f),
    )
}

/** Работа идёт — видно и из списка, с дорогой обратно к объекту. */
@Composable
private fun WorkingLine(state: DesktopState, onOpen: (InboxItem) -> Unit) {
    val working by state.working.collectAsState()
    val work = working ?: return
    Row(
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
            .glassPanel(RoundedCornerShape(12.dp))
            .handCursor()
            .clickable {
                work.objectId?.let { id -> state.items.value.firstOrNull { it.obj.id == id }?.let(onOpen) }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(PointColors.cyan)
        Text(
            work.title + (work.stage?.let { " · $it" } ?: ""),
            style = PointType.small.copy(color = PointColors.soft),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Приём файла по ссылке (#727): ссылку видно и её можно отдать, ожидание — отменить. */
@Composable
private fun ReceiveWaiting(state: DesktopState, ways: NewObjectWays) {
    val awaiting by state.receiving.collectAsState()
    val wait = awaiting ?: return
    Column(
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().glassPanel().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Пусть отправят файл сюда", style = PointType.rowTitle)
        Text(wait.link, style = PointType.mono.copy(color = PointColors.soft), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(wait.failed ?: wait.status, style = PointType.note)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            PillButton("Скопировать ссылку", { ways.copyReceiveLink(wait.link) }, icon = GlassIcons.Copy, small = true)
            PillButton("Отменить", ways.cancelReceive, small = true)
        }
    }
}

/**
 * «Недавнее» одним списком (#880) с секциями времени (#884): «Сейчас», «Сегодня», «Вчера»,
 * «Раньше». Время — структура списка, а не подпись в каждой строке: справа остаётся час.
 */
@Composable
internal fun RecentList(
    state: DesktopState,
    items: List<InboxItem>,
    onOpen: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    query: String = "",
    linked: Boolean = true,
) {
    val journal by state.journal.collectAsState()
    val fresh by state.fresh.collectAsState()
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val remembered = remember(journal, items) {
        com.point.desktop.recentBesides(journal, items.map { it.obj.uri.value }.toSet())
    }
    val lines = remember(items, remembered, journal) { recentLines(items, remembered, journal) }
    val found = remember(lines, query) { lines.filter { recentMatches(it, query) } }

    // Список листается до конца памяти (#1098): сразу видна страница, дальше — по просьбе.
    var shown by remember(found.size) { mutableStateOf(RECENT_PAGE) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        com.point.core.flow.byTimeSection(found.take(shown), now, zone) { it.at }.forEach { (section, rows) ->
            GroupHeader(section.label)
            rows.forEach { line ->
                val id = (line as? RecentLine.Live)?.item?.obj?.id
                GlassRow(
                    title = line.name(),
                    subtitle = line.note(),
                    icon = kindIcon(line.kind()),
                    tint = kindTint(line.kind()),
                    iconDescription = kindLabel(line.kind()),
                    selected = id != null && id == selectedId,
                    onClick = {
                        when (line) {
                            is RecentLine.Live -> onOpen(line.item)
                            is RecentLine.Kept -> state.openAgain(line.entry, onOpen)
                        }
                    },
                ) {
                    if (id != null && id in fresh) Text("новое", style = PointType.note.copy(color = PointColors.cyan))
                    Text(com.point.core.flow.rowTimeLabel(line.at, now, zone), style = PointType.mono)
                }
            }
        }

        if (shown < found.size) {
            Text(
                com.point.core.ui.showMoreLabel(found.size - shown),
                style = PointType.small.copy(color = PointColors.link),
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).handCursor()
                    .clickable { shown += RECENT_PAGE }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        when {
            // Как принести объект, сказано под «Новым объектом» прямо над списком.
            lines.isEmpty() -> EmptyNote("Пока пусто")
            found.isEmpty() -> EmptyNote("В «Недавнем» такого нет")
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = PointType.small, modifier = Modifier.padding(horizontal = 10.dp))
}

/**
 * Как принести сюда объект — одна строка под «Новым объектом», словами, которые правда сработают: поделиться с телефона можно,
 * только когда устройства связаны (решение владельца 20.10.2026 — вход для этого и нужен).
 */
internal fun howToBring(linked: Boolean): String =
    if (linked) {
        "Бросьте файл в окно, нажмите Ctrl+V или поделитесь с телефона."
    } else {
        "Бросьте файл в окно или нажмите Ctrl+V."
    }
