package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import com.point.desktop.DesktopState
import com.point.desktop.InboxItem
import com.point.desktop.JournalEntry
import com.point.desktop.sourceLabel
import com.point.desktop.whenLabel
import java.time.ZoneId

@Composable
fun Conveyor(state: DesktopState, item: InboxItem) {
    val journal by state.journal.collectAsState()
    val now = rememberNow()
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            modifier = Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Source(item, onCopyFact = state::copyFact, questionName = { id ->
                state.questionName(id, item.obj.state)
            })
            Path(journal.firstOrNull { it.path == item.obj.uri.value }, now)
        }

        LiveEnd(
            state,
            item,
            modifier = Modifier.weight(0.42f).widthIn(min = 260.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun Source(
    item: InboxItem,
    onCopyFact: (String) -> Unit = {},
    questionName: (com.point.core.model.CapabilityId) -> String? = { null },
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(1.dp, PointColors.border, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PointColors.violet.copy(alpha = 0.16f))
                    .border(1.dp, PointColors.violet.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(kindMark(item.obj.state.kind), style = PointType.title.copy(color = PointColors.violet))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.obj.metadata["name"] ?: "Объект",
                    style = PointType.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Суть, когда она понята, — вместо голого типа (P7: результат, не механизм).
                Text(
                    item.obj.metadata[com.point.core.flow.META_SEMANTIC_SUMMARY]
                        ?: kindLabel(item.obj.state.kind),
                    style = PointType.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Preview(item)
        Knowledge(item, onCopyFact, questionName)
    }
}

/** Сам объект виден сразу: текст читается, картинка показана (P2/P3 — экран без объекта был дефектом). */
@Composable
private fun Preview(item: InboxItem) {
    when (item.obj.state.kind) {
        ObjectKind.TEXT -> {
            val text = remember(item.obj.uri.value) {
                runCatching { java.io.File(item.obj.uri.value).readText() }.getOrNull()
            }
            if (!text.isNullOrBlank()) {
                Text(
                    text.take(PREVIEW_CHARS),
                    style = PointType.body.copy(fontSize = PointType.small.fontSize),
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PointColors.window.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        ObjectKind.IMAGE -> {
            val bitmap = remember(item.obj.uri.value) {
                runCatching {
                    java.io.File(item.obj.uri.value).inputStream().use {
                        androidx.compose.ui.res.loadImageBitmap(it)
                    }
                }.getOrNull()
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun Knowledge(
    item: InboxItem,
    onCopyFact: (String) -> Unit,
    questionName: (com.point.core.model.CapabilityId) -> String?,
) {
    val facts = com.point.core.flow.knowledgeRows(item.obj.metadata)
    val questions = com.point.core.flow.openQuestions(item.obj.metadata, questionName)
    if (facts.isEmpty() && questions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (facts.isNotEmpty()) Text("ПОНЯЛ", style = PointType.label.copy(color = PointColors.cyan))
        facts.forEach { fact ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCopyFact(fact.value) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(PointColors.cyan, CircleShape))
                    Text(
                        "${fact.name} · ${fact.value}" +
                            if (fact.confirmed) " · подтверждено вами" else "",
                        style = PointType.body.copy(fontSize = PointType.small.fontSize),
                    )
                }

                // Спор виден (P8); «ещё» — другие значения того же вида, не спор.
                if (fact.disputed.isNotEmpty()) {
                    Text(
                        "или: " + fact.disputed.joinToString(", "),
                        style = PointType.small,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                if (fact.more.isNotEmpty()) {
                    Text(
                        "ещё: " + fact.more.joinToString(", "),
                        style = PointType.small,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
            }
        }
        questions.forEach { q ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(PointColors.muted, CircleShape))
                Text(
                    "${q.name} · " + com.point.core.flow.openQuestionLabel(q.state),
                    style = PointType.small,
                )
            }
        }
    }
}

private const val PREVIEW_CHARS = 2_000

@Composable
private fun Path(entry: JournalEntry?, now: Long) {
    if (entry == null) return
    val zone = remember { ZoneId.systemDefault() }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, PointColors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text("ПУТЬ", style = PointType.label)
        PathStop(
            dot = PointColors.violet,
            title = "Приехал · ${sourceLabel(entry.source)}",
            note = null,
            time = whenLabel(entry.at, now, zone),
        )
        entry.steps.forEach { step ->
            PathStop(

                dot = if (step.ok) PointColors.cyan else PointColors.muted,
                title = step.title,
                note = step.note.takeIf { it.isNotBlank() },
                time = whenLabel(step.at, now, zone),
            )
        }
        if (entry.steps.isEmpty()) {
            Text("Пока ничего не делали — действия справа", style = PointType.small)
        }
    }
}

@Composable
private fun PathStop(dot: Color, title: String, note: String?, time: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PointType.body.copy(fontSize = PointType.small.fontSize))
            note?.let { Text(it, style = PointType.small, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text(time, style = PointType.small)
    }
}

@Composable
private fun LiveEnd(state: DesktopState, item: InboxItem, modifier: Modifier = Modifier) {
    val working by state.working.collectAsState()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {

        working?.let { Working(it) { state.cancelWork() } }

        // Один список: свои и телефонные вместе, порядок — по пользе, недоступное — с причиной.
        val actions = state.actionsFor(item)
        if (actions.isNotEmpty()) {
            val primary = actions.indexOfFirst { it.unavailable == null }
            Section("ЧТО МОЖНО СДЕЛАТЬ") {
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
        }
    }
}

@Composable
private fun Working(work: com.point.desktop.Working, onCancel: () -> Unit) {
    var now by remember(work.startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(work.startedAt) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(1.dp, PointColors.cyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(work.title, style = PointType.body)
        Text(
            listOfNotNull(work.stage, secondsWord(now - work.startedAt)).joinToString(" · "),
            style = PointType.small,
        )
        Text(
            "Отменить",
            style = PointType.small.copy(color = PointColors.violet),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel)
                .padding(vertical = 4.dp),
        )
    }
}

private fun secondsWord(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    if (s >= 60) {
        val m = s / 60
        return "$m " + when {
            m % 100 in 11..14 -> "минут"
            m % 10 == 1L -> "минуту"
            m % 10 in 2..4 -> "минуты"
            else -> "минут"
        }
    }
    return "$s " + when {
        s % 100 in 11..14 -> "секунд"
        s % 10 == 1L -> "секунда"
        s % 10 in 2..4 -> "секунды"
        else -> "секунд"
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = PointType.label)
        content()
    }
}

@Composable
private fun Station(
    title: String,
    accent: Color,
    where: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(
                1.dp,
                if (primary) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = if (primary) 14.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Text(title, style = PointType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        where?.let { Text(it, style = PointType.small) }
        Text("→", style = PointType.small)
    }
}

/** Недоступное действие видно с причиной, а не скрыто (PC5) — и по клику причина повторяется. */
@Composable
private fun MutedStation(title: String, where: String?, reason: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PointColors.border.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(PointColors.muted, CircleShape))
            Text(
                title,
                style = PointType.body.copy(color = PointColors.muted),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            where?.let { Text(it, style = PointType.small) }
        }
        Text(reason, style = PointType.small, modifier = Modifier.padding(start = 20.dp))
    }
}

private fun kindMark(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "IMG"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "TXT"
    ObjectKind.URL -> "URL"
    ObjectKind.ZIP -> "ZIP"
    ObjectKind.OFFICE -> "DOC"
    ObjectKind.COLLECTION -> "SET"
    else -> "•"
}

private fun kindLabel(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "Изображение"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "Текст"
    ObjectKind.URL -> "Ссылка"
    ObjectKind.ZIP -> "Архив"
    ObjectKind.OFFICE -> "Документ"
    ObjectKind.COLLECTION -> "Набор"
    else -> "Файл"
}

internal val DOCK_HINT = "Брось файл сюда"
