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
        Knowledge(
            item,
            onCopyFact = state::copyFact,
            questionName = { id -> state.questionName(id, item.obj.state) },
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

        // Сам текст — после знания о нём, как на телефоне (#898).
        if (item.obj.state.kind == ObjectKind.TEXT) {
            Text(kindLabel(item.obj.state.kind).uppercase(), style = PointType.label)
            Preview(item)
        }

        working?.let { Working(it) { state.cancelWork() } }

        val actions = state.actionsFor(item)
        if (actions.isNotEmpty()) {

            // Причина, общая для всех действий, сказана подписью объекта — у действий
            // остаётся их обещание (#874).
            val sharedReason = com.point.core.flow.sharedUnusableReason(
                actions.map { it.bubble?.unusableReason },
            )

            // Группы по смыслу — те же, что на телефоне (#879). Раньше здесь был один
            // список «Что можно сделать»: порядок совпадал с телефонным, но человеку это
            // было не видно. Действие без пузыря (просьба к телефону) идёт последней
            // группой — у него нет своего намерения, кроме «отправить».
            val primary = actions.indexOfFirst { it.unavailable == null }

            // «Извлечь» ведёт, только когда есть что извлекать (#1101) — правило общее с
            // телефоном.
            val useFirst = com.point.core.ui.knowsUsableValue(item.obj.state) ||
                !com.point.core.ui.promisesExtraction(actions.mapNotNull { it.bubble })
            val grouped = com.point.core.ui.actionGroupOrder(useFirst).mapNotNull { group ->
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
