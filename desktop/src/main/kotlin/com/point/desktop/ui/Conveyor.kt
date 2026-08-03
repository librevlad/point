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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/**
 * Конвейер (#285, мокап 2a): объект и то, что с ним можно сделать, — на одном экране.
 *
 * Показывается ровно то, что у ПК действительно есть: сам объект, что в нём понято, пройденный
 * путь и живой конец с действиями. Полоса пути перестала быть заглушкой в #407 — компьютер
 * наконец помнит, откуда объект приехал и что с ним делали.
 *
 * Главное на экране — **передача**: секция «Отправить» показывает, что объект умеет уехать на
 * телефон, и каким действием он там встретится.
 */
@Composable
fun Conveyor(state: DesktopState, item: InboxItem) {
    val journal by state.journal.collectAsState()
    val now = rememberNow()
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Source(item)
            Path(journal.firstOrNull { it.path == item.obj.uri.value }, now)
        }

        LiveEnd(state, item, modifier = Modifier.width(380.dp).fillMaxHeight())
    }
}

/** Источник конвейера: что за объект, чем он оказался и что в нём понято. */
@Composable
private fun Source(item: InboxItem) {
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
                Text(kindLabel(item.obj.state.kind), style = PointType.small)
            }
        }

        val facts = item.obj.metadata.filterKeys { it.startsWith("entity.") }
        if (facts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ПОНЯЛ", style = PointType.label.copy(color = PointColors.cyan))
                facts.entries.take(4).forEach { (key, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(PointColors.cyan, CircleShape))
                        Text("${factName(key)} · $value", style = PointType.body.copy(fontSize = PointType.small.fontSize))
                    }
                }
            }
        }
    }
}

/**
 * Пройденный путь объекта (#407): откуда приехал и что с ним делали.
 *
 * Об объекте, которого компьютер не помнит, полоса молчит целиком — пустая рамка с заголовком
 * обещала бы историю, которой нет. Неудачные станции остаются на месте и приглушены: провал,
 * стёртый из пути, — это не аккуратность, а враньё.
 */
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
                // Голубой — сделанное, приглушённый — то, что не вышло. Красного в палитре ПК нет,
                // и заводить его ради одной строки значило бы разойтись с дизайн-системой.
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

/** Одна станция пути: точка, что сделали, чем кончилось и когда. */
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

/**
 * Живой конец: что можно сделать прямо сейчас.
 *
 * Две секции. «На этом компьютере» — действия ПК. «Отправить» — передача: объект уезжает на
 * телефон, и человек сразу видит, каким действием он там встретится.
 */
@Composable
private fun LiveEnd(state: DesktopState, item: InboxItem, modifier: Modifier = Modifier) {
    val phoneActions = state.phoneActionsFor(item)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val pcActions = state.bubblesFor(item)
        // Заголовок без единого действия под ним — обещание пустоты. Нет действий — нет секции.
        if (pcActions.isNotEmpty()) {
            Section("НА ЭТОМ КОМПЬЮТЕРЕ") {
                pcActions.forEach { bubble ->
                    Station(bubble.title, PointColors.violet) { state.onBubble(item, bubble) }
                }
            }
        }

        Section("ОТПРАВИТЬ") {
            if (phoneActions.isEmpty()) {
                // Телефон ещё не рассказал, что умеет: молчать было бы хуже — человек решил бы,
                // что передача сломана, хотя связи просто ещё не было.
                Text(
                    "Телефон пока не сказал, что умеет. Откройте Point на телефоне — действия появятся здесь",
                    style = PointType.small,
                )
            }
            phoneActions.forEach { action ->
                Station("${action.label} · телефон", PointColors.cyan) { state.sendToPhone(item, action) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = PointType.label)
        content()
    }
}

/** Станция конвейера — одно действие: точка-акцент, название, стрелка вперёд. */
@Composable
private fun Station(title: String, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Text(title, style = PointType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("→", style = PointType.small)
    }
}

/** Короткий знак вида объекта для плашки — вместо иконочного шрифта, которого у ПК нет. */
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

private fun factName(key: String): String = when (key.removePrefix("entity.")) {
    "phone" -> "Телефон"
    "email" -> "Почта"
    "url" -> "Ссылка"
    "address" -> "Адрес"
    "date" -> "Дата"
    "card" -> "Карта"
    "amount" -> "Сумма"
    "track" -> "Накладная"
    else -> key.removePrefix("entity.").replaceFirstChar { it.uppercase() }
}

/** Заголовок пустого дока и подпись под ним — вынесены, чтобы текст жил рядом с конвейером. */
internal val DOCK_HINT = "Брось файл сюда"
