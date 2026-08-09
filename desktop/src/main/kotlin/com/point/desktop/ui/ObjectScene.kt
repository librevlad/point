package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.desktop.InboxItem
import com.point.desktop.JournalEntry
import com.point.desktop.sourceLabel
import com.point.desktop.whenLabel
import java.time.ZoneId

/** Сам объект виден сразу: текст читается, картинка показана (P2/P3). */
@Composable
internal fun Preview(item: InboxItem) {
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
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        else -> Unit
    }
}

@Composable
internal fun Knowledge(
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

/** Путь-хроника: складная строка — разворачивается по клику. */
@Composable
internal fun FoldedPath(entry: JournalEntry?, now: Long) {
    if (entry == null) return
    val zone = remember { ZoneId.systemDefault() }
    var open by remember(entry.path) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, PointColors.border, RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ПУТЬ", style = PointType.label)
            Text(
                if (open) "▾" else "▸ ${entry.steps.size + 1}",
                style = PointType.small,
            )
        }
        if (open) {
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
                Text("Пока ничего не делали — действия ниже", style = PointType.small)
            }
        }
    }
}

@Composable
internal fun PathStop(dot: Color, title: String, note: String?, time: String) {
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
internal fun Working(work: com.point.desktop.Working, onCancel: () -> Unit) {
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
            .background(PointColors.surface)
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

internal fun secondsWord(millis: Long): String {
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
internal fun Station(
    title: String,
    accent: Color,
    where: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PointColors.surface)
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

/** Недоступное действие видно с причиной, а не скрыто (PC5). */
@Composable
internal fun MutedStation(title: String, where: String?, reason: String, onClick: () -> Unit) {
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

internal fun kindMark(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "IMG"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "TXT"
    ObjectKind.URL -> "URL"
    ObjectKind.ZIP -> "ZIP"
    ObjectKind.OFFICE -> "DOC"
    ObjectKind.COLLECTION -> "SET"
    else -> "•"
}

internal fun kindLabel(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "Изображение"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "Текст"
    ObjectKind.URL -> "Ссылка"
    ObjectKind.ZIP -> "Архив"
    ObjectKind.OFFICE -> "Документ"
    ObjectKind.COLLECTION -> "Набор"
    else -> "Файл"
}

internal const val PREVIEW_CHARS = 2_000
