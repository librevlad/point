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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.ObjectKind
import com.point.core.ui.bubbleIcon
import com.point.desktop.InboxItem
import com.point.desktop.JournalEntry
import com.point.desktop.sourceLabel
import com.point.desktop.whenLabel
import java.time.ZoneId

/**
 * Превью объекта. Портал — только за знаком вида, когда содержимого не показать:
 * за текстом и картинкой портала нет (решение владельца 2026-08-09).
 */
@Composable
internal fun PortalPreview(item: InboxItem) {
    // Снимок — сам себе опознание, его видно наверху. Текст — нет: четырнадцать строк
    // сырого текста занимали весь экран, а вид, знание и действия оставались за нижним краем. Текст
    // теперь стоит там же, где на телефоне, — ниже знания (#898).
    if (item.obj.state.kind == ObjectKind.IMAGE) {
        Preview(item)
    } else {
        // Кольцо было пустым, и объект узнавался только по имени файла в шапке (#879).
        // Значок вида — тот же, что на телефоне: он лежит в общей таблице с #849.
        // Портал компактнее телефонного: в окне вертикаль дороже, площадь принадлежит
        // действиям, а не иллюстрации.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PortalHalo(size = 132.dp)

            // Та же марка, что на телефоне (#825): она живёт в общем шве, и компьютер не
            // рисует свой знак. Плоская иконка делала объект строкой из списка приложений.
            com.point.core.ui.KindMarkIcon(
                mark = com.point.core.ui.kindMarkOf(item.obj),
                size = 74.dp,
                contentDescription = com.point.core.ui.kindMarkLabel(
                    com.point.core.ui.kindMarkOf(item.obj),
                ),
            )
        }
    }
}

/** Портал телефона, перенесённый на ПК: свечение и два встречных кольца. */
@Composable
internal fun PortalHalo(size: androidx.compose.ui.unit.Dp, intensity: Float = 1f) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "portal")
    val spin by transition.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(9000, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "spin",
    )
    val spinBack by transition.animateFloat(
        360f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(6500, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "spinBack",
    )
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val c = center
        val rad = this.size.minDimension / 2f
        val a = intensity.coerceIn(0f, 1.4f)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                0f to PointColors.violet.copy(alpha = 0.28f * a),
                0.55f to PointColors.cyan.copy(alpha = 0.13f * a),
                1f to Color.Transparent,
                center = c, radius = rad,
            ),
            radius = rad,
        )
        val core = Color(0xFFEAF0FF)
        val glow = Color(0xFFB39DFF)
        val bloom = listOf(0.14f to 4.0f, 0.9f to 1.0f)
        rotate(spin, c) {
            for ((alpha, widthMul) in bloom) {
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color.Transparent, PointColors.cyan, glow, core, glow, PointColors.cyan, Color.Transparent),
                        center = c,
                    ),
                    radius = rad * 0.80f,
                    style = Stroke(width = rad * 0.055f * widthMul),
                    alpha = (alpha * a).coerceIn(0f, 1f),
                )
            }
        }
        rotate(spinBack, c) {
            for ((alpha, widthMul) in bloom) {
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color.Transparent, PointColors.violet, core, PointColors.violet, Color.Transparent),
                        center = c,
                    ),
                    radius = rad * 0.54f,
                    style = Stroke(width = rad * 0.042f * widthMul),
                    alpha = (alpha * a).coerceIn(0f, 1f),
                )
            }
        }
    }
}

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
                    // В окне 475 px четырнадцать строк — это весь экран (#898).
                    maxLines = 6,
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

                // Строка документа при значении — подпись, а не второе значение (#782).
                fact.said?.let { said ->
                    Text(said, style = PointType.small, modifier = Modifier.padding(start = 14.dp))
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
        // Та же строка, что на телефоне (#901): и время, и честность про затянувшееся
        // ожидание. Раньше компьютер говорил «12 секунд» и про долгое ожидание молчал.
        Text(
            listOfNotNull(
                work.stage,
                com.point.core.flow.waitingLine(
                    elapsed = ((now - work.startedAt) / 1000).toInt().coerceAtLeast(0),
                    network = work.network,
                ),
            ).joinToString(" · "),
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
    icon: String = "ai",
    note: String? = null,
    appearIndex: Int = 0,
    onClick: () -> Unit,
) {
    PortalRow(
        title = title,
        subtitle = listOfNotNull(note, where).firstOrNull(),
        icon = bubbleIcon(icon),
        accent = accent,
        primary = primary,
        appearIndex = appearIndex,
        onClick = onClick,
    )
}

/** Недоступное действие видно с причиной, а не скрыто (PC5). */
@Composable
internal fun MutedStation(
    title: String,
    where: String?,
    reason: String,
    icon: String = "ai",
    appearIndex: Int = 0,
    onClick: () -> Unit,
) {
    // Недоступное действие видно с причиной, а не скрыто (PC5). Оно остаётся такой же
    // строкой, как остальные: гаснет плашка и название, а не форма.
    PortalRow(
        title = title,
        subtitle = listOfNotNull(reason, where).joinToString(" · "),
        icon = bubbleIcon(icon),
        accent = PointColors.muted,
        appearIndex = appearIndex,
        onClick = onClick,
    )
}

internal fun kindMark(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "IMG"
    ObjectKind.PDF -> "PDF"
    ObjectKind.TEXT -> "TXT"
    ObjectKind.URL -> "URL"
    ObjectKind.ZIP -> "ZIP"
    ObjectKind.OFFICE -> "DOC"
    ObjectKind.COLLECTION -> "SET"
    else -> "\u2022"
}

internal const val PREVIEW_CHARS = 2_000
