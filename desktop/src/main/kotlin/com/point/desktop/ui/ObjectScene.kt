package com.point.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.point.core.model.ObjectKind
import com.point.desktop.InboxItem
import com.point.core.ui.GlassIcons
import com.point.core.ui.bubbleIcon

/*
 * Содержимое объекта — правая колонка его экрана (макет 19.10.2026): снимок, найденное,
 * прочитанный текст, состав набора, идущая работа. В узком окне та же панель встаёт между
 * значениями и остальными действиями.
 */

/**
 * Текст, который Point знает у этого объекта (P2/P3, #995).
 *
 * Прочитанное показывается у любого объекта, а не только у текстового: текст документа —
 * знание самого документа, и человек, нажавший «Извлечь текст», обязан увидеть его здесь же.
 * Откуда его брать, решает общее с телефоном правило `shownTextRef`.
 *
 * [marks] — найденные значения: в тексте они подсвечены там, где сказаны (макет 19.10.2026), —
 * видно, откуда взялось знание.
 */
@Composable
internal fun Preview(item: InboxItem, marks: List<String> = emptyList(), onCopyText: ((String) -> Unit)? = null) {
    val ref = com.point.core.flow.shownTextRef(item.obj) ?: return
    val read = remember(ref) {
        runCatching {
            com.point.desktop.readTextHead(java.io.File(ref), com.point.core.ui.TEXT_PREVIEW_LOAD_LIMIT)
        }.getOrNull()
    }
    if (read == null || read.text.isBlank()) return
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(PointColors.cyan)
            Text(
                textTitle(item.obj.state.kind),
                style = PointType.small.copy(color = PointColors.text, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )

            // В буфер уходит весь прочитанный текст, а не показанная голова: копия не обрывается молча.
            onCopyText?.let { copy -> PillButton("Скопировать", { copy(ref) }, icon = GlassIcons.Copy, small = true) }
        }
        TextBody(read, marks)
    }
}

/** Подпись над текстом называет то, что под ним: у записи это расшифровка, у остального — текст. */
internal fun textTitle(kind: ObjectKind): String =
    if (kind == ObjectKind.AUDIO) "Расшифровка" else com.point.core.ui.kindLabel(ObjectKind.TEXT)

/**
 * Текст объекта на компьютере: свёрнут до нескольких строк и раскрывается по просьбе (#1086).
 *
 * Раньше здесь были пять строк с многоточием и всё: длинный текст на ПК нельзя было
 * дочитать, хотя телефон тот же объект раскрывал. Механика взята оттуда целиком — и надписи
 * тоже, они лежат в общем каталоге, а не переписаны сюда.
 */
@Composable
private fun TextBody(read: com.point.desktop.TextHead, marks: List<String>) {
    var expanded by remember(read.text) { mutableStateOf(false) }

    // Текст обрезается дважды: по символам (голова) и по строкам на экране. Кнопка обязана
    // знать про оба случая — иначе короткий, но высокий текст обрывается без выхода (#871).
    var clipped by remember(read.text) { mutableStateOf(false) }
    val head = remember(read.text) { com.point.core.ui.textPreviewHead(read.text) }
    val shown = if (expanded) read.text else head
    val marked = remember(shown, marks) { marked(shown, marks) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Text(
                marked,
                style = PointType.reading,
                // В узком окне четырнадцать строк — это весь экран (#898).
                maxLines = if (expanded) Int.MAX_VALUE else PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) clipped = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (clipped || head.length < read.text.length) {
            Text(
                if (expanded) {
                    com.point.core.ui.COLLAPSE_LABEL
                } else {
                    com.point.core.ui.expandTextLabel(read.text.length - head.length, read.atLimit)
                },
                style = PointType.small.copy(color = PointColors.link),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).handCursor()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }

        // Раскрыли — и оно упёрлось в предел чтения: сколько показано, сказано числом.
        if (expanded && read.atLimit) {
            Text(com.point.core.ui.truncatedPreviewNotice(read.text.length), style = PointType.note)
        }
    }
}

/** Текст с подсвеченными найденными значениями. */
private fun marked(text: String, marks: List<String>): AnnotatedString = buildAnnotatedString {
    var at = 0
    highlightRanges(text, marks).forEach { range ->
        append(text, at, range.first)
        withStyle(SpanStyle(background = PointColors.cyan.copy(alpha = 0.16f), color = PointColors.text)) {
            append(text, range.first, range.last + 1)
        }
        at = range.last + 1
    }
    append(text, at, text.length)
}

/**
 * Где в тексте стоят найденные значения: без учёта регистра, без наложений, по порядку.
 *
 * Значение короче двух знаков не ищется — «5» подсветило бы каждую пятёрку текста, а не ту,
 * что найдена.
 */
internal fun highlightRanges(text: String, values: Collection<String>): List<IntRange> {
    val found = values.map { it.trim() }.filter { it.length >= 2 }.distinct()
        .flatMap { value ->
            generateSequence(text.indexOf(value, ignoreCase = true).takeIf { it >= 0 }) { from ->
                text.indexOf(value, from + value.length, ignoreCase = true).takeIf { it >= 0 }
            }.map { it until it + value.length }.toList()
        }
        .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
    val kept = mutableListOf<IntRange>()
    found.forEach { range -> if (kept.none { range.first <= it.last }) kept += range }
    return kept
}

/**
 * Найденное у объекта (P2/P3): значение, спор прочтений и «ещё» — видны (P8), всё копируется
 * одной кнопкой рядом. Открытые вопросы — тише, ниже.
 */
@Composable
internal fun Knowledge(
    item: InboxItem,
    onCopyFact: (String) -> Unit,
    questionName: (com.point.core.model.CapabilityId) -> String?,

    /** Ключи знания, которые уже стоят заголовками разделов действий, — второй раз не нужны. */
    shownElsewhere: Set<String> = emptySet(),
) {
    val facts = com.point.core.flow.knowledgeRows(item.obj.metadata).filterNot { it.key in shownElsewhere }
    val questions = com.point.core.flow.openQuestions(item.obj.metadata, questionName)
    if (facts.isEmpty() && questions.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (facts.isNotEmpty()) {
            Text("НАЙДЕНО · ${facts.size}", style = PointType.label.copy(color = PointColors.muted), modifier = Modifier.padding(bottom = 6.dp))
        }
        facts.forEach { fact -> FactRow(fact, onCopyFact) }
        questions.forEach { q ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(PointColors.faint)
                Text("${q.name} · " + com.point.core.flow.openQuestionLabel(q.state), style = PointType.small)
            }
        }
    }
}

@Composable
private fun FactRow(fact: com.point.core.flow.KnowledgeRow, onCopy: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(PointColors.cyan)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = PointColors.muted)) { append(fact.name) }
                    append("  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(fact.value) }
                    if (fact.confirmed) withStyle(SpanStyle(color = PointColors.muted)) { append(" · подтверждено вами") }
                },
                style = PointType.small.copy(fontSize = 12.5.sp, color = PointColors.text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Строка документа при значении — подпись, а не второе значение (#782).
            fact.said?.let { Text(it, style = PointType.note, maxLines = 2, overflow = TextOverflow.Ellipsis) }

            // Спор виден (P8); «ещё» — другие значения того же вида, не спор.
            if (fact.disputed.isNotEmpty()) Text("или: " + fact.disputed.joinToString(", "), style = PointType.note)
            if (fact.more.isNotEmpty()) Text("ещё: " + fact.more.joinToString(", "), style = PointType.note)
        }
        PillButton("Скопировать", { onCopy(fact.value) }, small = true)
    }
}

/** Состав набора — своей панелью; вход раскрывает ребёнка объектом (#1099). */
@Composable
internal fun ChildrenPanel(children: List<String>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp)) {
        children.forEach { path ->
            val file = java.io.File(path)
            GlassRow(
                title = file.name,
                subtitle = null,
                icon = GlassIcons.Document,
                onClick = { onOpen(path) },
                minHeight = 44.dp,
                trailing = { Chevron() },
            )
        }
    }
}

/** Идущая работа над объектом: что делается, сколько ждём и честно о долгом ожидании. */
@Composable
internal fun Working(work: com.point.desktop.Working, onCancel: () -> Unit) {
    var now by remember(work.startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(work.startedAt) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Row(
        Modifier.fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(PointColors.cyan, size = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(work.title, style = PointType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                style = PointType.note,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PillButton("Отменить", onCancel, small = true)
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
        subtitle = note,
        mark = where,
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
    // Недоступное действие остаётся такой же строкой, как остальные: гаснет плашка и
    // название, а не форма.
    PortalRow(
        title = title,
        subtitle = reason,
        mark = where,
        icon = bubbleIcon(icon),
        accent = PointColors.muted,
        dim = true,
        appearIndex = appearIndex,
        onClick = onClick,
    )
}

/** Знак вида буквами — там, где знаку негде нарисоваться линией. */
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

/**
 * Сколько строк текста видно в свёрнутом виде. Сколько символов читать и сколько показывать
 * в голове — общее с телефоном знание (`TEXT_PREVIEW_LOAD_LIMIT`, `textPreviewHead`).
 */
internal const val PREVIEW_LINES = 6
