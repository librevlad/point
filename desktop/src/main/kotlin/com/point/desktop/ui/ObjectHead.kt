package com.point.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.point.core.ui.ObjectVerdict
import com.point.desktop.InboxItem
import com.point.desktop.JournalEntry
import com.point.desktop.JournalStep
import com.point.desktop.ObjectSource
import com.point.desktop.StepOutcome
import com.point.desktop.sourceLabel
import com.point.desktop.sourceShort
import com.point.desktop.whenLabel
import java.time.Instant
import java.time.ZoneId
import com.point.core.ui.GlassIcons
import com.point.core.ui.heroLine
import com.point.core.ui.drawObjectRing

/*
 * Шапка экрана объекта (макет 19.10.2026): кольцо с вещью, её имя тем же словом, что на
 * телефоне, мера и происхождение — и путь вещи полосой «История».
 */

/**
 * Кольцо вещи — граница, через которую она пришла. Внутри знак её вида, а у снимка — сам снимок:
 * он себе опознание, и знак вида поверх него лишний (#898).
 *
 * Рисунок кольца общий с телефоном (`drawObjectRing`).
 */
@Composable
internal fun ObjectRing(item: InboxItem, picture: ImageBitmap?, size: Dp = 84.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawObjectRing() }
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size * 66f / 84f).clip(CircleShape),
            )
        } else {
            val mark = com.point.core.ui.kindMarkOf(item.obj)
            Icon(
                heroLine(item.obj),
                // Знак называется тем же словом, что у телефона: это и есть вид вещи (#825).
                contentDescription = com.point.core.ui.kindMarkLabel(mark),
                tint = PointColors.text,
                modifier = Modifier.size(size * 32f / 84f),
            )
        }
    }
}

/** Шапка: кольцо, имя вещи, мера и откуда она, имя файла и вес. */
@Composable
internal fun ObjectHead(
    item: InboxItem,
    verdict: ObjectVerdict,
    entry: JournalEntry?,
    now: Long,
    zone: ZoneId,
    picture: ImageBitmap?,
    modifier: Modifier = Modifier,
    ring: Dp = 84.dp,
) {
    val bytes = remember(item.obj.uri.value, item.obj.metadata) { objectBytes(item) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (ring < 84.dp) 14.dp else 18.dp),
    ) {
        ObjectRing(item, picture, ring)
        Column(Modifier.weight(1f)) {
            Text(verdict.headline, style = PointType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            headLine(verdict, arrivalLine(entry, now, zone))?.let {
                Text(
                    it,
                    style = PointType.body.copy(fontSize = 13.sp, color = PointColors.soft),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            fileLine(item.obj.metadata["name"], bytes, verdict)?.let {
                Text(it, style = PointType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Вторая строка шапки: мера, подпись вещи, откуда и когда она пришла — через точку. */
internal fun headLine(verdict: ObjectVerdict, arrival: String?): String? =
    listOfNotNull(verdict.measure, verdict.subline, arrival)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { null }

/** Откуда и когда вещь пришла: «с телефона · сегодня 17:27». */
internal fun arrivalLine(entry: JournalEntry?, now: Long, zone: ZoneId): String? =
    entry?.let { sourceLabel(it.source) + " · " + whenLabel(it.at, now, zone) }

/**
 * Третья строка — чем вещь зовётся на диске и сколько весит. Имя, уже сказанное строкой выше,
 * второй раз не повторяется; вес, уже ставший мерой записи, — тоже.
 */
internal fun fileLine(name: String?, bytes: Long?, verdict: ObjectVerdict): String? {
    val file = name?.takeIf { it.isNotBlank() && it != verdict.headline && it != verdict.subline }
    val weight = bytes?.let { com.point.core.flow.humanWeight(it) }?.takeIf { it != verdict.measure }
    return listOfNotNull(file, weight).joinToString(" · ").ifBlank { null }
}

/** Вес вещи: знание о нём, а без него — сам файл; у найденного значения файла нет. */
private fun objectBytes(item: InboxItem): Long? =
    item.obj.metadata[com.point.core.flow.META_SIZE]?.toLongOrNull()
        ?: runCatching { java.io.File(item.obj.uri.value).takeIf { it.isFile }?.length() }.getOrNull()

/**
 * «История» — путь вещи полосой (макет 19.10.2026): откуда пришла, она сама, что с ней делали.
 *
 * Щелчок раскрывает тот же путь строками — с пояснением у каждого шага: сорвавшийся шаг обязан
 * быть виден со своей причиной, а не только словом «не вышло» в полосе.
 */
@Composable
internal fun History(entry: JournalEntry, current: String, now: Long, zone: ZoneId) {
    var open by remember(entry.path) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ИСТОРИЯ", style = PointType.label.copy(color = PointColors.muted))
            SegmentBar(historySegments(entry, current, zone), onClick = { open = !open })
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().glassPanel().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PathStop(PointColors.violet, "Приехал · ${sourceLabel(entry.source)}", null, whenLabel(entry.at, now, zone))
                entry.steps.forEach { step ->
                    PathStop(
                        dot = when (step.outcome) {
                            StepOutcome.DONE -> PointColors.cyan
                            StepOutcome.AWAITING -> PointColors.yellow
                            StepOutcome.FAILED -> PointColors.close
                        },
                        title = step.title,
                        note = step.note.takeIf { it.isNotBlank() },
                        time = whenLabel(step.at, now, zone),
                    )
                }
                if (entry.steps.isEmpty()) Text("Пока ничего не делали — действия рядом", style = PointType.note)
            }
        }
    }
}

/** Сколько последних шагов видно в полосе; раньше них — счёт скрытых. */
internal const val HISTORY_STEPS = 3

/** Сегменты полосы «История»: откуда, сама вещь, последние шаги. */
internal fun historySegments(entry: JournalEntry, current: String, zone: ZoneId): List<Segment> = buildList {
    add(Segment(sourceShort(entry.source), icon = sourceIcon(entry.source)))
    add(Segment(current, current = true))
    val hidden = entry.steps.size - HISTORY_STEPS
    if (hidden > 0) add(Segment("ещё $hidden", faded = true))
    entry.steps.takeLast(HISTORY_STEPS).forEach { step ->
        add(Segment(stepLabel(step, zone), faded = step.outcome == StepOutcome.FAILED))
    }
}

/** Шаг в полосе: что сделали и когда; несостоявшийся — чем кончился, а не временем. */
internal fun stepLabel(step: JournalStep, zone: ZoneId): String = when (step.outcome) {
    StepOutcome.DONE -> step.title + " · " + clockOf(step.at, zone)
    StepOutcome.FAILED -> step.title + " · не вышло"
    StepOutcome.AWAITING -> step.title + " · " + step.note.ifBlank { "ждёт" }
}

private fun clockOf(at: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(at).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

private fun sourceIcon(source: ObjectSource): ImageVector = when (source) {
    ObjectSource.PHONE_LAN, ObjectSource.PHONE_RELAY -> GlassIcons.Phone
    ObjectSource.DROPPED -> GlassIcons.Folder
    ObjectSource.CLIPBOARD -> GlassIcons.Clipboard
    ObjectSource.LOCAL -> GlassIcons.Screen
}

@Composable
internal fun PathStop(dot: Color, title: String, note: String?, time: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        StatusDot(dot, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PointType.rowTitle)
            note?.let { Text(it, style = PointType.note, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        Text(time, style = PointType.mono)
    }
}
