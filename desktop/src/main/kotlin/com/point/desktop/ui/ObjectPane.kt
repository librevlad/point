package com.point.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.point.core.flow.yieldLabel
import com.point.core.model.tr
import com.point.core.ui.SectionTopic
import com.point.core.ui.bubbleColor
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import java.time.ZoneId

/**
 * Экран объекта в окне компьютера (#836, макет 19.10.2026).
 *
 * Шапка — вещь и её путь. Ниже две колонки: действия по смыслу слева, содержимое справа —
 * результат стоит рядом с тем, из чего он (P4), а не под стеной строк. В узком окне колонки
 * встают одна под другую тем же порядком, что на телефоне: значения с их продолжениями, знание
 * и текст, остальные действия.
 */
@Composable
internal fun ObjectPane(
    state: DesktopState,
    item: InboxItem,
    modifier: Modifier = Modifier,
    invited: InboxItem? = null,
    onOpenInvited: (InboxItem) -> Unit = {},
) {
    val journal by state.journal.collectAsState()
    val working by state.working.collectAsState()
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val entry = journal.firstOrNull { it.path == item.obj.uri.value }
    val verdict = remember(item.obj) { com.point.core.ui.objectVerdict(item.obj) }
    val picture = rememberPicture(item)

    // Разделы действий — те же, что на телефоне (#879): найденное значение со своими
    // продолжениями, понимание вещи, новое из неё, сама вещь дальше.
    val actions = state.actionsFor(item)
    val sections = com.point.core.ui.actionSections(
        actions,
        { it.bubble },
        item.obj.state,
        com.point.core.ui.thingTitle(item.obj),
        com.point.core.ui.thingIsValue(item.obj),
        com.point.core.ui.makeTitle(item.obj),
    )
    val values = sections.filter { it.topic == SectionTopic.VALUE }
    val rest = sections.filter { it.topic != SectionTopic.VALUE }

    // Причина, общая для всех действий, сказана подписью объекта — у действий остаётся их
    // обещание (#874).
    val sharedReason = com.point.core.flow.sharedUnusableReason(actions.map { it.bubble?.unusableReason })

    // Значение, у которого есть продолжения, стоит заголовком своего раздела и не повторяется
    // строкой знания: одно значение — одно место.
    val shownElsewhere = values.mapTo(mutableSetOf()) { it.key }
    val work = working?.takeIf { it.objectId == null || it.objectId == item.obj.id }
    val children = remember(item.obj) { com.point.desktop.collectionChildren(item.obj) }
    val hasContent = work != null || picture != null || children.isNotEmpty() ||
        com.point.core.flow.shownTextRef(item.obj) != null ||
        com.point.core.flow.knowledgeRows(item.obj.metadata).any { it.key !in shownElsewhere } ||
        com.point.core.flow.openQuestions(item.obj.metadata) { id -> state.questionName(id, item.obj.state) }.isNotEmpty()

    val content: @Composable () -> Unit = {
        work?.let { Working(it) { state.cancelWork() } }
        picture?.let {
            // Обведённое мышью место — Focus объекта (#1379): действия над ним встают рядом.
            val turn = remember(item.obj.uri.value) { pictureTurn(item) }
            val places = com.point.core.flow.focusOf(item.obj.metadata, item.obj.id)?.places.orEmpty()
            PicturePanel(it, PictureArea(turn, places, { shown -> state.showArea(item, shown) }, { state.clearArea(item) }))
        }
        Knowledge(
            item,
            onCopyFact = state::copyFact,
            questionName = { id -> state.questionName(id, item.obj.state) },
            shownElsewhere = shownElsewhere,
        )
        // Сам текст — после знания о нём, как на телефоне (#898).
        Preview(item, marks = remember(item.obj.metadata) { textMarks(item.obj.metadata) }, onCopyText = state::copyText)
        if (children.isNotEmpty()) ChildrenPanel(children, state::openPath)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Пришло новое, пока человек здесь работает: не выдёргиваем — приглашаем.
        invited?.let { Invite(it, onOpenInvited) }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= TWO_COLUMNS) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ObjectHead(item, verdict, entry, now, zone, picture)
                    entry?.let { History(it, verdict.headline, now, zone) }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(if (hasContent) Modifier.width(ACTIONS_WIDTH) else Modifier.widthIn(max = 560.dp)) {
                            ActionSections(state, item, values, actions, sharedReason, atTop = true)
                            ActionSections(state, item, rest, actions, sharedReason, atTop = values.isEmpty())
                        }
                        if (hasContent) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                GroupHeader(tr("Содержимое"), top = 0.dp)
                                content()
                            }
                        }
                    }
                }
            } else {
                // Узкое окно — порядок телефона: вещь → значения → знание и текст → действия.
                // Путь уходит вниз, как прежний «ПУТЬ»: наверху ему место только рядом с колонками.
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ObjectHead(item, verdict, entry, now, zone, picture, ring = 64.dp)
                    Column {
                        ActionSections(state, item, values, actions, sharedReason, atTop = true)
                        if (hasContent) {
                            Column(
                                Modifier.padding(top = if (values.isEmpty()) 0.dp else 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { content() }
                        }
                        ActionSections(state, item, rest, actions, sharedReason, atTop = values.isEmpty() && !hasContent)
                    }
                    entry?.let { History(it, verdict.headline, now, zone) }
                }
            }
        }
    }
}

/** С какой ширины действия и содержимое стоят рядом, а не одно под другим. */
private val TWO_COLUMNS = 620.dp

/** Ширина колонки действий — по макету. */
private val ACTIONS_WIDTH = 330.dp

/** Значения, которые подсвечиваются в тексте: само знание, его прочтения и «ещё». */
internal fun textMarks(metadata: Map<String, String>): List<String> =
    com.point.core.flow.knowledgeRows(metadata).flatMap { row ->
        listOfNotNull(row.value, metadata[row.key]) + row.disputed + row.more
    }

/** Приглашение к пришедшему: строка над шапкой, щелчок открывает его. */
@Composable
private fun Invite(fresh: InboxItem, onOpen: (InboxItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(12.dp)).handCursor()
            .clickable { onOpen(fresh) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(PointColors.cyan)
        Text(
            tr("Пришло: ") + (fresh.obj.metadata["name"] ?: tr("объект")),
            style = PointType.small.copy(color = PointColors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(tr("открыть"), style = PointType.small.copy(color = PointColors.link))
    }
}

/**
 * Разделы действий объекта (#879 — то же правило и тот же код, что на телефоне).
 *
 * Раздел показывает вероятное, остальное — по просьбе: компьютер показывал всё подряд, и у
 * снимка выходила стена в два десятка одинаковых строк (ревью экранов 02.09.2026).
 *
 * Подсвечено только продолжение найденного — первая строка раздела, который ведёт. Прежде
 * «главное» считалось по плоскому списку до группировки, и подсветка садилась на случайную
 * строку в середине: у снимка ею оказалось «Открыть» тринадцатым по счёту.
 */
@Composable
private fun ActionSections(
    state: DesktopState,
    item: InboxItem,
    sections: List<com.point.core.ui.ActionSection<DesktopState.ActionChoice>>,
    actions: List<DesktopState.ActionChoice>,
    sharedReason: String?,
    atTop: Boolean,
) {
    sections.forEachIndexed { index, section ->
        GroupHeader(section.title, top = if (index == 0 && atTop) 0.dp else 14.dp)
        var expanded by remember(section.key, section.items.size) { mutableStateOf(false) }
        val rows = section.items
        val shown = if (expanded) rows else rows.take(com.point.core.ui.shownAtOnce(section))
        Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp)) {
            if (section.topic == SectionTopic.VALUE) ValueLine(section, item, onCopy = state::copyFact)
            shown.forEachIndexed { rowIndex, action ->
                ActionRow(state, item, action, actions.indexOf(action), section.leads && rowIndex == 0, sharedReason)
            }
        }
        val hidden = rows.size - shown.size
        if (hidden > 0 || expanded) {
            Text(
                if (expanded) tr("Свернуть") else com.point.core.ui.showMoreLabel(hidden),
                style = PointType.small.copy(color = PointColors.link),
                modifier = Modifier.padding(start = 6.dp, top = 6.dp).clip(RoundedCornerShape(8.dp)).handCursor()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    state: DesktopState,
    item: InboxItem,
    action: DesktopState.ActionChoice,
    index: Int,
    primary: Boolean,
    sharedReason: String?,
) {
    when {
        action.unavailable != null -> MutedStation(
            action.title,
            where = if (action.onPhone) tr("на телефоне") else null,
            reason = action.unavailable,
            icon = action.icon,
            appearIndex = index,
        ) { state.say(action.unavailable) }

        action.bubble != null -> Station(
            action.title,
            bubbleColor(action.icon),
            primary = primary,
            icon = action.icon,
            note = yieldLabel(action.bubble.yields, action.bubble.unusableReason.takeIf { it != sharedReason }),
            appearIndex = index,
        ) { state.onBubble(item, action.bubble) }

        action.remote != null -> Station(
            action.title,
            bubbleColor(action.icon),
            where = tr("на телефоне"),
            primary = primary,
            icon = action.icon,
            appearIndex = index,
        ) { state.sendToPhone(item, action.remote) }
    }
}

/**
 * Значение раздела — сверху его панели: само значение и спор прочтений там, где человек решает,
 * что с ним делать (P8). Копируется кнопкой рядом, как строка знания.
 */
@Composable
private fun ValueLine(
    section: com.point.core.ui.ActionSection<DesktopState.ActionChoice>,
    item: InboxItem,
    onCopy: (String) -> Unit,
) {
    val row = com.point.core.flow.knowledgeRows(item.obj.metadata).firstOrNull { it.key == section.key }

    // Смысл, уже сказанный подписью объекта («Созвон с Анной»), не повторяется.
    val value = section.value?.takeUnless { it == item.obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY] }

    // В буфер — само значение: номер без подписи вида, ссылка со схемой. У смысла вещи
    // значения нет, есть суть.
    val raw = item.obj.metadata[section.key]
        ?.takeIf { section.key.startsWith(com.point.core.flow.META_ENTITY_PREFIX) && it.isNotBlank() }
    val copy = value?.let { raw ?: it }
    val disputed = row?.disputed.orEmpty()
    val more = row?.more.orEmpty()
    if (value == null && disputed.isEmpty() && more.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            value?.let { Text(it, style = PointType.title.copy(fontSize = PointType.reading.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (disputed.isNotEmpty()) Text(tr("или: ") + disputed.joinToString(", "), style = PointType.note)
            if (more.isNotEmpty()) Text(tr("ещё: ") + more.joinToString(", "), style = PointType.note)
        }
        copy?.let { PillButton(tr("Скопировать"), { onCopy(it) }, small = true) }
    }
}
