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
 * Экран объекта в окне компьютера (#836).
 *
 * Портал и знание живут в `ObjectScene.kt`; здесь — сам экран: что за чем стоит и
 * какие действия под ним.
 */
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
        // Разделы действий — те же, что на телефоне (#879): найденное значение со своими
        // продолжениями, сама вещь, её понимание. Считаются здесь, потому что значения
        // стоят сразу под объектом, а остальное — после его содержимого.
        val actions = state.actionsFor(item)
        val sections = com.point.core.ui.actionSections(
            actions,
            { it.bubble },
            item.obj.state,
            com.point.core.ui.thingTitle(item.obj),
            com.point.core.ui.thingIsValue(item.obj),
            com.point.core.ui.makeTitle(item.obj),
        )
        val valueSections = sections.filter { it.topic == com.point.core.ui.SectionTopic.VALUE }

        // Причина, общая для всех действий, сказана подписью объекта — у действий
        // остаётся их обещание (#874).
        val sharedReason = com.point.core.flow.sharedUnusableReason(
            actions.map { it.bubble?.unusableReason },
        )
        ActionSections(state, item, valueSections, actions, sharedReason)

        // Значение, у которого есть продолжения, стоит заголовком своего раздела и не
        // повторяется строкой знания: одно значение — одно место.
        Knowledge(
            item,
            onCopyFact = state::copyFact,
            questionName = { id -> state.questionName(id, item.obj.state) },
            shownElsewhere = valueSections.mapTo(mutableSetOf()) { it.key },
        )

        // Дети набора — своим разделом; вход раскрывает ребёнка объектом (#1099).
        val children = com.point.desktop.collectionChildren(item.obj)
        if (children.isNotEmpty()) {
            Text("СОДЕРЖИМОЕ · " + children.size, style = PointType.label)
            children.forEach { path ->
                val childName = java.io.File(path).name
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PointColors.window.copy(alpha = 0.55f))
                        .clickable { state.openPath(path) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        childName,
                        style = PointType.small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("открыть →", style = PointType.small.copy(color = PointColors.cyan))
                }
            }
        }

        // Сам текст — после знания о нём, как на телефоне (#898). Прочитанное видно у любого
        // объекта, а не только у текстового (#995): «Извлечь текст» кладёт текст знанием на
        // сам документ, и человеку он нужен на том же экране, где он нажал.
        if (com.point.core.flow.shownTextRef(item.obj) != null) {
            Text(kindLabel(ObjectKind.TEXT).uppercase(), style = PointType.label)
            Preview(item)
        }

        working?.let { Working(it) { state.cancelWork() } }

        ActionSections(
            state,
            item,
            sections.filter { it.topic != com.point.core.ui.SectionTopic.VALUE },
            actions,
            sharedReason,
        )

        FoldedPath(journal.firstOrNull { it.path == item.obj.uri.value }, now)
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
) {
    sections.forEach { section ->
        if (section.topic == com.point.core.ui.SectionTopic.VALUE) {
            ValueHeading(section, item, onCopy = state::copyFact)
        } else {
            Text(section.title.uppercase(), style = PointType.label)
        }

        var expanded by androidx.compose.runtime.remember(section.key, section.items.size) {
            androidx.compose.runtime.mutableStateOf(false)
        }
        val rows = section.items
        val shown = if (expanded) rows else rows.take(com.point.core.ui.shownAtOnce(section))
        shown.forEachIndexed { rowIndex, action ->
            val i = actions.indexOf(action)
            val primaryHere = section.leads && rowIndex == 0
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
                    primary = primaryHere,
                    icon = action.icon,
                    note = yieldLabel(
                        action.bubble.yields,
                        action.bubble.unusableReason.takeIf { it != sharedReason },
                    ),
                    appearIndex = i,
                ) { state.onBubble(item, action.bubble) }

                action.remote != null -> Station(
                    action.title,
                    bubbleColor(action.icon),
                    where = "на телефоне",
                    primary = primaryHere,
                    icon = action.icon,
                    appearIndex = i,
                ) { state.sendToPhone(item, action.remote) }
            }
        }
        val hidden = rows.size - shown.size
        if (hidden > 0 || expanded) {
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть" else "Показать ещё $hidden", style = PointType.small)
            }
        }
    }
}

/**
 * Заголовок раздела значения: название, само значение и спор прочтений — там, где человек
 * решает, что с ним делать (P8). Значение копируется щелчком, как строка знания.
 */
@Composable
private fun ValueHeading(
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
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .let { base -> if (copy != null) base.clickable { onCopy(copy) } else base }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(section.title.uppercase(), style = PointType.label)
        value?.let { Text(it, style = PointType.body, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        row?.disputed?.takeIf { it.isNotEmpty() }?.let { other ->
            Text("или: " + other.joinToString(", "), style = PointType.small)
        }
        row?.more?.takeIf { it.isNotEmpty() }?.let { more ->
            Text("ещё: " + more.joinToString(", "), style = PointType.small)
        }
    }
}
