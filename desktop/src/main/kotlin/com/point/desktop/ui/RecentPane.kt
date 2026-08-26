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
import kotlinx.coroutines.launch
import com.point.core.flow.yieldLabel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.point.core.model.ObjectKind
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
 * Список «Недавнее» в окне компьютера (#836).
 *
 * Раньше жил внутри `CompactApp.kt` вместе с окном, объектом и настройками — 942 строки в
 * одном файле. Искать причину живой ошибки в них дороже, чем в двухстах.
 */
/** Список: «сейчас» + «было раньше» + двери входа. */
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

        val remembered = remember(journal, items) {
            com.point.desktop.recentBesides(journal, items.map { it.obj.uri.value }.toSet())
        }

        // Один список с одним именем — «Недавнее» (#880). Раньше здесь были «СЕЙЧАС» и
        // «ИСТОРИЯ»: телефон показывал свои объекты, компьютер — журнал событий, и это
        // читалось как два разных продукта.
        if (items.isNotEmpty() || remembered.isNotEmpty()) {
            Text("НЕДАВНЕЕ", style = PointType.label, modifier = Modifier.padding(top = 4.dp))
        }

        // Время — структура списка, а не подпись в каждой строке. Правило общее с телефоном.
        //
        // Объекты, открытые прямо сейчас, и записи журнала — два источника одного списка, и
        // резать на секции их нужно вместе. Пока каждый резался отдельно, «СЕГОДНЯ» стояло
        // в списке дважды: сначала над живыми объектами, потом над журналом (#884).
        val lines = remember(items, remembered, journal) { recentLines(items, remembered, journal) }

        // Список листается до конца памяти (#1098): сразу видна страница, дальше — по просьбе.
        // Прежде окно обрывалось на восьмой записи молча, и остального будто не существовало.
        var shown by remember(lines.size) { mutableStateOf(RECENT_PAGE) }
        com.point.core.flow.byTimeSection(lines.take(shown), now, zone) { it.at }
            .forEach { (section, rows) ->
                Text(
                    section.label.uppercase(),
                    style = PointType.label.copy(color = PointColors.text.copy(alpha = 0.72f)),
                    modifier = Modifier.padding(top = 6.dp),
                )
                rows.forEach { line ->
                    when (line) {
                        is RecentLine.Live -> ListRow(
                            name = line.item.obj.metadata["name"] ?: "Объект",
                            note = recentNote(line.item.obj.state.kind, line.source),
                            accent = true,
                            kind = line.item.obj.state.kind,
                            clock = com.point.core.flow.rowTimeLabel(line.at, now, zone),
                            fresh = line.item.obj.id in fresh,
                        ) { onOpen(line.item) }

                        is RecentLine.Kept -> ListRow(
                            name = line.entry.name.ifBlank { "Объект" },
                            note = recentNote(
                                runCatching { ObjectKind.valueOf(line.entry.kind) }
                                    .getOrDefault(ObjectKind.UNKNOWN),
                                line.entry.source,
                            ),
                            accent = false,
                            kind = runCatching { ObjectKind.valueOf(line.entry.kind) }
                                .getOrDefault(ObjectKind.UNKNOWN),
                            clock = com.point.core.flow.rowTimeLabel(line.at, now, zone),
                        ) { state.openAgain(line.entry, onOpen) }
                    }
                }
            }

        if (shown < lines.size) {
            Text(
                com.point.core.ui.showMoreLabel(lines.size - shown),
                style = PointType.small.copy(color = PointColors.violet),
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { shown += RECENT_PAGE }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }

        if (items.isEmpty() && remembered.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Point ждёт объект.\nПоделитесь с телефона, бросьте файл сюда или возьмите буфер.",
                style = PointType.small,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Станции — не история (#880): это способы породить новый объект, а не прошлые
        // объекты. Раньше они продолжали тот же список и читались как его хвост.
        Text("СОЗДАТЬ ОБЪЕКТ", style = PointType.label)

        // Приём файла есть и здесь (#727): «и на пк тоже и прием и отправка».
        val awaiting by state.receiving.collectAsState()
        if (awaiting == null) {
            Station(
                "Принять файл по ссылке",
                bubbleColor("link"),
                icon = "link",
                note = "дайте ссылку тому, кто пришлёт вам файл",
            ) { onReceiveFile() }
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
        // Строй названий и пояснения — те же, что на телефоне (#895).
        Station(
            "Взять из буфера",
            bubbleColor("copy"),
            icon = "copy",
            note = "то, что вы скопировали, станет объектом",
        ) { onTakeClipboard() }
        Station(
            "Снять экран целиком",
            bubbleColor("camera"),
            icon = "camera",
            note = "снимок всего, что сейчас на экране",
        ) { onGrabScreen() }
    }
}

@Composable
private fun ListRow(
    name: String,
    note: String,
    accent: Boolean,
    kind: ObjectKind,
    clock: String,
    fresh: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) PointColors.surface else PointColors.surfaceDeep.copy(alpha = 0.6f))
            .border(1.dp, PointColors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Значок вида вместо безымянной точки (#880): точка занимала место и не говорила
        // ничего — ни что это за объект, ни на что он похож на экране объекта.
        androidx.compose.material3.Icon(
            imageVector = com.point.core.ui.kindIcon(kind),
            contentDescription = com.point.core.ui.kindLabel(kind),
            tint = if (accent) PointColors.text else PointColors.muted,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = PointType.body.copy(fontSize = PointType.small.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = PointType.small.copy(color = PointColors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fresh) Text("новое", style = PointType.small.copy(color = PointColors.cyan))

        // Час справа: время сказано заголовком секции, здесь остаётся «когда именно».
        Text(clock, style = PointType.small.copy(color = PointColors.muted.copy(alpha = 0.8f)))
    }
}
