package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiServiceLine
import com.point.core.flow.KEY_SECTION_TITLE
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiKey
import com.point.core.flow.looksLikeApiKey
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.OutcomeCard
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard

/**
 * Раздел «Ключи AI» на телефоне (#834).
 *
 * Жил внутри `KeyScreen.kt` вместе с пятью другими разделами: 1039 строк и 46 composable
 * в одном файле. Разделы не знают друг о друге, но делили файл и его состояние.
 */
/**
 * Все известные сервисы списком — в том порядке, в каком Point к ним обращается
 * (#699). В строке: имя, что умеет, есть ли ключ и последний факт о нём.
 */
@Composable
internal fun KeySection(
    screen: AiKeysScreen,
    note: String?,
    errand: KeyErrand?,
    checking: String?,
    verdict: KeyVerdict?,
    verdictFor: String?,
    onSave: (UserAiKey) -> Unit,
    onCheck: (UserAiKey) -> Unit,
    onCheckAll: () -> Unit,
    onPasteKey: () -> String?,
    onForgetKey: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = KEY_SECTION_TITLE, modifier = Modifier.padding(bottom = if (note == null) 9.dp else 0.dp))

    if (note != null) OutcomeBanner(message = note, outcome = Outcome.FAILED)

    if (errand != null) {
        OutcomeCard(
            title = com.point.core.flow.keyErrandWhy(errand.action),
            outcome = Outcome.NONE,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Экран ключей — панель состояния, а не документация (#902). Наверху две мысли: очередь
    // и необязательность ключа; всё остальное ждёт за «Как это работает».
    Text(
        com.point.core.flow.AI_CHAIN_WHAT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var howOpen by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { howOpen = !howOpen }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(
            if (howOpen) "Свернуть" else "Как это работает",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Reveal(howOpen) {
        Text(
            com.point.core.flow.AI_CHAIN_MORE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val checkingAll = checking == CHECK_ALL_SERVICES

    // Счёт и массовая проверка — одной тихой строкой. Раньше «Проверить все» стояло главным
    // действием экрана и спорило со списком, ради которого сюда и заходят.
    PortalRow(
        title = com.point.core.flow.aiKeysCount(screen.keys),
        // Состояние, а не механика: «сам Point ничего не проверяет» уехало в «Как это
        // работает» — оно объясняет устройство, а здесь стоит то, что происходит (#902).
        subtitle = if (checkingAll) {
            "Point спрашивает каждый сервис одним коротким словом. Ваш объект никуда не уходит."
        } else {
            screen.checkedLine
        },
        onClick = onCheckAll,
        icon = null,
        primary = false,
        chevron = false,
        enabled = checking == null,
        subtitleMaxLines = 2,
        trailing = {
            Text(
                if (checkingAll) "Проверяю…" else "Проверить все",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        modifier = Modifier.graphicsLayer { alpha = if (checking == null) 1f else 0.45f },
    )

    var open by rememberSaveable { mutableStateOf<String?>(null) }

    // Общее сказано заголовком группы один раз, а не девятью одинаковыми хвостами в строках
    // (решение владельца по мокапам 12.08.2026 — вариант Б).
    var index = 0
    com.point.core.flow.aiServiceGroups(screen.services).forEach { (group, rows) ->
        Spacer(Modifier.height(4.dp))
        SectionLabel(group.title)
        // Почему «Ваши ключи» стоят не подряд: 05, 09, 12 — их места в общей очереди (#911).
        if (group == com.point.core.flow.AiServiceGroup.MINE) {
            Text(
                com.point.core.flow.AI_MINE_KEEP_PLACE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { line ->
            ServiceRow(
                line = line,
                checking = checking == line.providerId,
                open = open == line.providerId,
                index = index++,
                onToggle = { open = if (open == line.providerId) null else line.providerId },
            )
            Reveal(open == line.providerId) {
                ServiceEditor(
                    line = line,
                    saved = screen.keys.of(line.providerId),
                    checking = checking == line.providerId,
                    verdict = verdict.takeIf { verdictFor == line.providerId },
                    onSave = onSave,
                    onCheck = onCheck,
                    onPasteKey = onPasteKey,
                    onForgetKey = onForgetKey,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }

    if (errand != null && verdict is KeyVerdict.Works) {
        Spacer(Modifier.height(6.dp))
        PortalRow(
            title = "Вернуться к «${errand.objectName}»",
            subtitle = "«${errand.action}» ждёт там — уже без приписки про ключ. Тапнуть по нему " +
                "Point за вас не станет.",
            onClick = onLeave,

            primary = true,
            chevron = false,
            subtitleMaxLines = 3,
        )
    }
}

@Composable
internal fun ServiceRow(
    line: AiServiceLine,
    checking: Boolean,
    open: Boolean,
    index: Int,
    onToggle: () -> Unit,
) {
    PortalRow(
        // Номер — место в очереди обращения. Девять имён подряд читались как меню
        // равноправных настроек; номер объясняет порядок без единого слова (#902).
        // Он вторичен: главное в строке — имя сервиса (#911).
        title = line.name,
        place = line.place,
        // Что умеет сервис — по раскрытию: в закрытой строке это девять абзацев подряд.
        // Здесь остаётся то, что отличает эту строку от соседних (#887).
        subtitle = serviceState(line, checking, open),
        onClick = onToggle,
        icon = null,
        primary = false,
        chevron = false,
        subtitleMaxLines = 2,
        appearIndex = index,
        trailing = {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (open) 90f else 0f },
            )
        },
    )
}

/**
 * Что стоит во второй строке сервиса. Общее про группу уже сказано её заголовком, поэтому
 * здесь остаётся личное: свой ключ, последний факт, ход проверки.
 */
internal fun serviceState(line: AiServiceLine, checking: Boolean, open: Boolean): String? = when {
    checking -> "проверяю…"
    open && line.mine -> line.what + "\n" + line.keyLine + " · " + line.factLine
    open -> line.what
    line.mine -> "${line.keyLine} · ${line.factLine}"
    // «Ответил» — ожидаемое, о нём строка молчит. Новость — отказ, исчерпанный лимит или
    // молчание сервиса: их видно, не раскрывая строку (#902).
    else -> line.trouble
}

@Composable
internal fun ServiceEditor(
    line: AiServiceLine,
    saved: UserAiKey?,
    checking: Boolean,
    verdict: KeyVerdict?,
    onSave: (UserAiKey) -> Unit,
    onCheck: (UserAiKey) -> Unit,
    onPasteKey: () -> String?,
    onForgetKey: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val provider = AI_PROVIDERS.firstOrNull { it.id == line.providerId }
    val draft = rememberSaveable(line.providerId, saved, saver = KeyDraft.Saver) {
        KeyDraft(saved ?: UserAiKey(line.providerId, ""))
    }

    Column(
        modifier = Modifier.fillMaxWidth().portalCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (provider != null) {
            PortalRow(
                title = "Открыть сайт ${provider.name}",
                subtitle = listOfNotNull(
                    "Там выдают ключ: заведите аккаунт, скопируйте ключ — и вернитесь сюда.",
                    provider.freeNote,
                ).joinToString(" "),
                onClick = { onOpenUrl(provider.keyUrl) },
                icon = bubbleIcon("open"),
                accent = bubbleColor("open"),
                chevron = false,
                subtitleMaxLines = 3,
            )
        }

        OutlinedTextField(
            value = draft.key,
            onValueChange = {
                draft.key = it
                draft.pasteNote = ""
            },
            label = { Text("Ключ ${line.name}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.key.isBlank()) {
            PortalRow(
                title = "Вставить из буфера",
                subtitle = "Скопировали ключ на странице сервиса — он встанет сюда одним тапом.",
                onClick = {
                    val pasted = onPasteKey()
                    if (looksLikeApiKey(pasted)) {
                        draft.key = pasted!!.trim()
                        draft.pasteNote = ""
                    } else {

                        draft.pasteNote = "В буфере нет ключа — скопируйте его на странице сервиса и вернитесь."
                    }
                },
                icon = bubbleIcon("copy"),
                chevron = false,
            )
        }
        if (draft.pasteNote.isNotEmpty()) {
            Text(
                draft.pasteNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DisclosureRow(
            title = "Модель и адрес",
            subtitle = listOf(draft.model, draft.baseUrl).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "как у сервиса — набирать не нужно" },
            open = draft.advancedOpen,
            onToggle = { draft.advancedOpen = !draft.advancedOpen },
        )
        Reveal(draft.advancedOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft.model = it },
                    label = { Text("Модель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft.baseUrl = it },
                    label = { Text("Адрес сервиса") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val canCheck = draft.key.isNotBlank() && !checking
        PortalRow(

            title = if (checking) "Проверяю…" else "Проверить и включить",
            subtitle = "Point спросит сервис одним коротким словом. Ваш объект при этом никуда не отправляется.",
            onClick = { onCheck(draft.entered()) },
            icon = bubbleIcon(AI_ICON),

            primary = verdict !is KeyVerdict.Works,
            chevron = false,
            enabled = canCheck,
            subtitleMaxLines = 3,
            modifier = Modifier.graphicsLayer { alpha = if (canCheck) 1f else 0.45f },
        )

        if (draft.key.isNotBlank() && verdict !is KeyVerdict.Works) {
            TextButton(onClick = { onSave(draft.entered()) }) {
                Text("Сохранить без проверки", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (line.mine) {
            PortalRow(
                title = "Удалить ключ",
                subtitle = "Point сотрёт его с устройства. ${line.name} снова замолчит, пока не впишете новый.",
                onClick = {

                    draft.key = ""
                    draft.pasteNote = ""
                    onForgetKey(line.providerId)
                },
                chevron = false,
                subtitleMaxLines = 3,
            )
        }

        when (verdict) {
            is KeyVerdict.Works -> OutcomeCard(
                title = "Работает — сервис ответил: «${verdict.reply}». Ключ сохранён.",
                detail = "Теперь «Понять», «Перевести», «Спросить AI» и расшифровка записи работают.",
                outcome = Outcome.DONE,
                modifier = Modifier.fillMaxWidth(),
            )
            is KeyVerdict.Refused -> OutcomeCard(
                title = verdict.what,
                detail = verdict.fix,
                outcome = Outcome.FAILED,
                modifier = Modifier.fillMaxWidth(),
            )
            null -> Unit
        }
    }
}
