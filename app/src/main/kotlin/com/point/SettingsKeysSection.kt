package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiProvider
import com.point.core.flow.AiServiceLine
import com.point.core.flow.KEY_SECTION_TITLE
import com.point.core.flow.NO_KEY_IN_CLIPBOARD
import com.point.core.flow.PASTE_ANY_KEY
import com.point.core.flow.PASTE_ANY_KEY_WHAT
import com.point.core.flow.WHOSE_KEY_DISMISS
import com.point.core.flow.WHOSE_KEY_TITLE
import com.point.core.flow.WHOSE_KEY_WHAT
import com.point.core.flow.KeyVerdict
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.UserAiKey
import com.point.core.flow.accountIn
import com.point.core.flow.keyErrandService
import com.point.core.flow.looksLikeApiKey
import com.point.core.flow.providerOfKey
import com.point.core.model.tr
import com.point.core.ui.GlassGroup
import com.point.core.ui.GroupHeader
import com.point.core.ui.GroupSeam
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.OutcomeCard
import com.point.core.ui.PortalRow
import com.point.core.ui.SmallPill
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointText

/**
 * Раздел «Ключи AI» на телефоне (#834).
 *
 * Жил внутри `KeyScreen.kt` вместе с пятью другими разделами: 1039 строк и 46 composable
 * в одном файле. Разделы не знают друг о друге, но делили файл и его состояние.
 */
/**
 * Все известные сервисы списком — в том порядке, в каком Point к ним обращается
 * (#699). В строке: имя, что умеет, есть ли ключ и последний факт о нём.
 *
 * Над списком — дорога короче списка: ключ из буфера встаёт к своему сервису сам и сразу
 * проверяется. Раньше человек искал строку сервиса среди двенадцати, раскрывал её, вставлял
 * и отдельно нажимал «Проверить» — и ключ, вставленный не в ту строку, просто «не подходил».
 */
@Composable
internal fun KeySection(
    screen: AiKeysScreen,
    note: String?,
    errand: KeyErrand?,
    checking: String?,
    verdict: KeyVerdict?,
    verdictFor: String?,
    privacyLevel: PrivacyLevel,
    onSave: (UserAiKey) -> Unit,
    onCheck: (UserAiKey) -> Unit,
    onCheckAll: () -> Unit,
    onPasteKey: () -> String?,
    onForgetKey: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit,
) {
    SectionTopBar(title = KEY_SECTION_TITLE, onBack = onBack, modifier = Modifier.padding(bottom = if (note == null) 9.dp else 0.dp))

    if (note != null) OutcomeBanner(message = note, outcome = Outcome.FAILED)

    var open by rememberSaveable { mutableStateOf<String?>(null) }

    // Ключ из буфера без приметы сервиса: ждёт ответа человека «чей он».
    var unclaimed by rememberSaveable { mutableStateOf<String?>(null) }

    // Ключ, вставленный сверху, стоит в поле своего сервиса и тогда, когда проверка его не
    // пустила: человек видит, что именно проверяли, и может поправить.
    var pastedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var pastedKey by rememberSaveable { mutableStateOf("") }
    var pasteNote by rememberSaveable { mutableStateOf<String?>(null) }

    fun take(key: String, providerId: String) {
        val provider = AI_PROVIDERS.firstOrNull { it.id == providerId }
        val entered = (screen.keys.of(providerId) ?: UserAiKey(providerId, "")).copy(apiKey = key.trim())
        unclaimed = null
        pasteNote = null
        pastedFor = providerId
        pastedKey = entered.apiKey
        open = providerId

        // Без номера аккаунта Workers AI проверять не по чему: ключ встаёт в поле, номер
        // человек впишет рядом.
        val waitsForAccount = provider?.account != null && provider.accountIn(entered.baseUrl).isEmpty()
        if (!waitsForAccount) onCheck(entered)
    }

    // Дверь обратно — под приговором, если он виден в раскрытом сервисе; иначе на месте довода.
    val back = errand != null && verdict is KeyVerdict.Works
    val backInEditor = back && open == verdictFor

    if (errand != null) {
        if (back && !backInEditor) {
            ReturnDoor(errand, onLeave)
        } else if (!back) {
            OutcomeCard(
                title = com.point.core.flow.keyErrandWhy(errand.action),
                outcome = Outcome.NONE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    TakeKey(
        // Пришли с действия — Point называет, куда идти за ключом: первый бесплатный сервис,
        // которому эта вещь по силам. Двенадцать равных строк — выбор, к которому человек
        // не готов (P1).
        suggested = errand?.takeIf { !back }?.let { keyErrandService(screen.keys, it.kind, privacyLevel) },
        enabled = checking == null,
        onOpenUrl = onOpenUrl,
        onPaste = {
            val pasted = onPasteKey()?.trim()
            when {
                pasted == null || !looksLikeApiKey(pasted) -> {
                    unclaimed = null
                    pasteNote = NO_KEY_IN_CLIPBOARD
                }
                else -> providerOfKey(pasted)?.let { take(pasted, it.id) } ?: run {
                    pasteNote = null
                    unclaimed = pasted
                }
            }
        },
    )
    pasteNote?.let { Text(it, style = PointText.note) }
    unclaimed?.let { key -> WhoseKey(onPick = { take(key, it) }, onDismiss = { unclaimed = null }) }

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
            if (howOpen) tr("Свернуть") else tr("Как это работает"),
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
            tr("Point спрашивает каждый сервис одним коротким словом. Ваш объект никуда не уходит.")
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
                if (checkingAll) tr("Проверяю…") else tr("Проверить все"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        modifier = Modifier.graphicsLayer { alpha = if (checking == null) 1f else 0.45f },
    )

    // Общее сказано заголовком группы один раз, а не девятью одинаковыми хвостами в строках
    // (решение владельца по мокапам 12.08.2026 — вариант Б). Строки группы — одна панель
    // стекла, как в остальных настройках.
    var index = 0
    com.point.core.flow.aiServiceGroups(screen.services).forEach { (group, rows) ->
        GroupHeader(group.title, top = 6.dp)
        // Почему «Ваши ключи» стоят не подряд: 05, 09, 12 — их места в общей очереди (#911).
        if (group == com.point.core.flow.AiServiceGroup.MINE) {
            Text(
                com.point.core.flow.AI_MINE_KEEP_PLACE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassGroup {
            rows.forEachIndexed { at, line ->
                if (at > 0) GroupSeam(start = 14.dp)
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
                        pasted = pastedKey.takeIf { pastedFor == line.providerId && it.isNotBlank() },
                        checking = checking == line.providerId,
                        verdict = verdict.takeIf { verdictFor == line.providerId },
                        errand = errand,
                        onSave = onSave,
                        onCheck = onCheck,
                        onPasteKey = onPasteKey,
                        onForgetKey = onForgetKey,
                        onOpenUrl = onOpenUrl,
                        onLeave = onLeave,
                        onElsewhere = ::take,
                    )
                }
            }
        }
    }
}

/** Как взять ключ: сайт, где его дают даром, и вставка из буфера. */
@Composable
private fun TakeKey(
    suggested: AiProvider?,
    enabled: Boolean,
    onOpenUrl: (String) -> Unit,
    onPaste: () -> Unit,
) {
    GlassGroup {
        if (suggested != null) {
            PortalRow(
                title = tr("Открыть сайт {0}", suggested.name),
                subtitle = listOfNotNull(offerSentence(suggested), tr("Скопируйте там ключ и вернитесь сюда.")).joinToString(" "),
                onClick = { onOpenUrl(suggested.keyUrl) },
                icon = bubbleIcon("open"),
                accent = bubbleColor("open"),
                chevron = false,
                surface = false,
                subtitleMaxLines = 3,
            )
            GroupSeam()
        }
        PortalRow(
            title = PASTE_ANY_KEY,
            subtitle = tr("{0} Ваш объект при этом никуда не уходит.", PASTE_ANY_KEY_WHAT),
            onClick = onPaste,
            icon = bubbleIcon("copy"),
            chevron = false,
            surface = false,
            enabled = enabled,
            subtitleMaxLines = 3,
        )
    }
}

/**
 * Ключ без приметы сервиса: спросить, а не гадать. Ключ одного сервиса, отправленный на
 * пробу другому, — это чужой секрет в чужих руках.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoseKey(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().portalCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(WHOSE_KEY_TITLE, style = PointText.rowTitle)
        Text(WHOSE_KEY_WHAT, style = PointText.note)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AI_PROVIDERS.forEach { provider -> SmallPill(provider.name, onClick = { onPick(provider.id) }) }
        }
        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(WHOSE_KEY_DISMISS, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Дверь к вещи, ради которой пришли за ключом. */
@Composable
internal fun ReturnDoor(errand: KeyErrand, onLeave: () -> Unit) {
    PortalRow(
        title = tr("Вернуться к «{0}»", errand.objectName),
        subtitle = tr("«{0}» ждёт там — уже без приписки про ключ. Нажать на него " +
            "Point за вас не станет.", errand.action),
        onClick = onLeave,
        primary = true,
        chevron = false,
        subtitleMaxLines = 3,
    )
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
        subtitle = com.point.core.flow.aiServiceState(line, checking, open),
        onClick = onToggle,
        icon = null,
        primary = false,
        chevron = false,
        surface = false,
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

/** Цена ключа отдельным предложением: «Бесплатный уровень.» — она стоит перед советом. */
internal fun offerSentence(provider: AiProvider): String? =
    provider.freeNote?.replaceFirstChar { it.uppercase() }?.let { "$it." }
