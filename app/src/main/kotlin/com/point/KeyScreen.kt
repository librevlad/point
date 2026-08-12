package com.point

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_KEY_WHY
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFact
import com.point.core.flow.AiOutcome
import com.point.core.flow.AiProvider
import com.point.core.flow.AiServiceLine
import com.point.core.flow.KeyVerdict
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.OWN_SERVICE_ID
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.YOLO_TITLE
import com.point.core.flow.YOLO_WHAT
import com.point.core.flow.aiCheckedLine
import com.point.core.flow.aiKeysSummary
import com.point.core.flow.aiServiceLines
import com.point.core.flow.looksLikeApiKey
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.OutcomeCard
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme

@Composable
fun KeyScreen(
    screen: AiKeysScreen,

    note: String? = null,

    errand: KeyErrand? = null,
    onSave: (UserAiKey) -> Unit,
    onCancel: () -> Unit,

    checking: String? = null,

    verdict: KeyVerdict? = null,

    verdictFor: String? = null,

    onCheck: (UserAiKey) -> Unit = {},

    onCheckAll: () -> Unit = {},

    onPasteKey: () -> String? = { null },

    onForgetKey: (String) -> Unit = {},
    soundEnabled: Boolean = true,
    onToggleSound: (Boolean) -> Unit = {},

    privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit = {},

    cloudEnabled: Boolean = false,
    onToggleCloud: (Boolean) -> Unit = {},

    yoloEnabled: Boolean = false,
    onToggleYolo: (Boolean) -> Unit = {},

    onOpenUrl: (String) -> Unit = {},

    onOpenDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    var section by rememberSaveable(note, errand, checking, verdict) {
        mutableStateOf(
            if (note != null || errand != null || checking != null || verdict != null) {
                SettingsSection.KEY
            } else {
                null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            when (section) {

                null -> BackToRoot(onCancel)
                else -> Unit
            }
            when (section) {
                null -> SettingsList(
                    keyLine = aiKeysSummary(screen.keys),
                    cloudEnabled = cloudEnabled,
                    privacyLevel = privacyLevel,
                    yoloEnabled = yoloEnabled,
                    soundEnabled = soundEnabled,
                    onToggleSound = onToggleSound,
                    onOpen = { section = it },
                    onOpenDevices = onOpenDevices,
                )

                SettingsSection.KEY -> KeySection(
                    screen = screen,
                    note = note,
                    errand = errand,
                    checking = checking,
                    verdict = verdict,
                    verdictFor = verdictFor,
                    onSave = onSave,
                    onCheck = onCheck,
                    onCheckAll = onCheckAll,
                    onPasteKey = onPasteKey,
                    onForgetKey = onForgetKey,
                    onOpenUrl = onOpenUrl,
                    onBack = { section = null },

                    onLeave = onCancel,
                )

                SettingsSection.PRIVACY -> PrivacySection(
                    cloudEnabled = cloudEnabled,
                    onToggleCloud = onToggleCloud,
                    yoloEnabled = yoloEnabled,
                    onToggleYolo = onToggleYolo,
                    privacyLevel = privacyLevel,
                    onPickPrivacyLevel = onPickPrivacyLevel,
                    onBack = { section = null },
                )

                SettingsSection.APP -> AppSection(
                    soundEnabled = soundEnabled,
                    onToggleSound = onToggleSound,
                    onBack = { section = null },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        TextButton(onClick = onCancel) {

            Text(
                if (verdict is KeyVerdict.Works) "Готово" else "Отмена",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class SettingsSection { KEY, PRIVACY, APP }

@Composable
private fun SettingsList(
    keyLine: String,
    cloudEnabled: Boolean,
    privacyLevel: PrivacyLevel,
    yoloEnabled: Boolean,
    soundEnabled: Boolean,
    onToggleSound: (Boolean) -> Unit,
    onOpen: (SettingsSection) -> Unit,
    onOpenDevices: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {

        ScreenHeader(title = SETTINGS_TITLE, modifier = Modifier.padding(bottom = 2.dp))

        SettingsGroup(AI_GROUP_TITLE) {

            SettingsRow(
                title = KEY_SECTION_TITLE,
                subtitle = keyLine,
                onClick = { onOpen(SettingsSection.KEY) },
                appearIndex = 0,
            )
            GroupSeam()
            SettingsRow(
                title = PRIVACY_SECTION_TITLE,

                subtitle = (if (cloudEnabled) "Облако разрешено" else "Облако выключено") +
                    " · ${privacyLevel.title}" + (if (yoloEnabled) " · $YOLO_TITLE" else ""),
                onClick = { onOpen(SettingsSection.PRIVACY) },
                appearIndex = 1,
            )
        }

        SettingsGroup(ACCOUNT_GROUP_TITLE) {

            SettingsRow(
                title = MY_DEVICES_TITLE,
                subtitle = "Вход, круг устройств и выход.",
                onClick = onOpenDevices,
                appearIndex = 2,
            )
        }

        SettingsGroup(APP_SECTION_TITLE) {

            SettingsRow(
                title = SOUND_TITLE,
                subtitle = "Тихий фирменный отклик на каждое действие.",
                onClick = { onOpen(SettingsSection.APP) },
                appearIndex = 3,
                trailing = { Switch(checked = soundEnabled, onCheckedChange = onToggleSound) },
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().portalCard()) {
        SectionLabel(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 2.dp),
        )
        rows()
    }
}

@Composable
private fun GroupSeam() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    appearIndex: Int,
    trailing: (@Composable () -> Unit)? = null,
) {
    PortalRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        chevron = trailing == null,
        surface = false,
        subtitleMaxLines = Int.MAX_VALUE,
        appearIndex = appearIndex,
        trailing = trailing,
    )
}

private val GroupGap = 16.dp

@Composable
private fun BackToRoot(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("← Назад", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackToList(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("← $SETTINGS_TITLE", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Все известные сервисы списком — в том порядке, в каком Point к ним обращается
 * (#699). В строке: имя, что умеет, есть ли ключ и последний факт о нём.
 */
@Composable
private fun KeySection(
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

    Text(
        "$AI_KEY_WHY Ключ живёт только на этом устройстве, и Point работает на " +
            "вашей квоте. Ниже — все сервисы, к которым Point обращается, сверху вниз.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val checkingAll = checking == CHECK_ALL_SERVICES
    PortalRow(
        title = if (checkingAll) "Проверяю…" else "Проверить все",
        subtitle = if (checkingAll) {
            "Point спрашивает каждый сервис одним коротким словом. Ваш объект никуда не уходит."
        } else {
            "${screen.checkedLine}. Сам Point ничего не проверяет — только по этому тапу."
        },
        onClick = onCheckAll,
        icon = bubbleIcon(AI_ICON),
        accent = bubbleColor(AI_ICON),
        primary = !checkingAll,
        chevron = false,
        enabled = checking == null,
        subtitleMaxLines = 3,
        modifier = Modifier.graphicsLayer { alpha = if (checking == null) 1f else 0.45f },
    )

    var open by rememberSaveable { mutableStateOf<String?>(null) }

    screen.services.forEachIndexed { index, line ->
        ServiceRow(
            line = line,
            checking = checking == line.providerId,
            open = open == line.providerId,
            index = index,
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
private fun ServiceRow(
    line: AiServiceLine,
    checking: Boolean,
    open: Boolean,
    index: Int,
    onToggle: () -> Unit,
) {
    PortalRow(
        title = line.name,
        subtitle = "${line.what}\n${line.keyLine} · ${if (checking) "проверяю…" else line.factLine}",
        onClick = onToggle,
        icon = bubbleIcon(AI_ICON),
        accent = bubbleColor(AI_ICON),
        primary = line.mine,
        chevron = false,
        subtitleMaxLines = 4,
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

@Composable
private fun ServiceEditor(
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

@Composable
private fun PrivacySection(
    cloudEnabled: Boolean,
    onToggleCloud: (Boolean) -> Unit,
    yoloEnabled: Boolean,
    onToggleYolo: (Boolean) -> Unit,
    privacyLevel: PrivacyLevel,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = PRIVACY_SECTION_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    SwitchCard(
        title = "Отправка в облако",
        // #688, охота 2026-08-10: обещание «с названием того, куда он уедет» система
        // не выполняла — подписи действий называли только «сервис». Кто читает объект,
        // теперь видно в «Ключи AI» (#699): вся цепочка по порядку, с последним фактом.
        description = "Разрешает показывать объект моделям — по вашему тапу. Выключите, и " +
            "Point спросит заново. Выложить файл по открытой ссылке этим тумблером нельзя: " +
            "про такое спрашивают каждый раз.",
        checked = cloudEnabled,
        onCheckedChange = onToggleCloud,
    )

    Spacer(Modifier.height(6.dp))
    SwitchCard(
        title = YOLO_TITLE,
        description = YOLO_WHAT,
        checked = yoloEnabled,
        onCheckedChange = onToggleYolo,
    )

    Spacer(Modifier.height(6.dp))
    SectionLabel(PRIVACY_SETTING_TITLE)
    Text(
        PRIVACY_SETTING_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrivacyLevel.entries.forEachIndexed { index, level ->
        PortalRow(
            title = level.title,

            subtitle = level.what,
            onClick = { onPickPrivacyLevel(level) },

            primary = level == privacyLevel,
            chevron = false,
            subtitleMaxLines = 4,
            appearIndex = index,
        )
    }
}

@Composable
private fun AppSection(
    soundEnabled: Boolean,
    onToggleSound: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = APP_SECTION_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    SwitchCard(
        title = SOUND_TITLE,
        description = "Тихий фирменный отклик на каждое действие. Вибрация управляется системной настройкой касаний.",
        checked = soundEnabled,
        onCheckedChange = onToggleSound,
    )
}

private const val AI_GROUP_TITLE = "AI и облако"
private const val ACCOUNT_GROUP_TITLE = "Аккаунт"

private const val KEY_SECTION_TITLE = "Ключи AI"
private const val PRIVACY_SECTION_TITLE = "Отправка и приватность"
private const val APP_SECTION_TITLE = "Приложение"
private const val SOUND_TITLE = "Звук действий"


private class KeyDraft(saved: UserAiKey) {
    var providerId by mutableStateOf(saved.providerId)
    var key by mutableStateOf(saved.apiKey)
    var model by mutableStateOf(saved.model)
    var baseUrl by mutableStateOf(saved.baseUrl)

    var pasteNote by mutableStateOf("")

    var advancedOpen by mutableStateOf(false)

    fun entered() = UserAiKey(providerId, key.trim(), model.trim(), baseUrl.trim())

    companion object {
        val Saver = listSaver<KeyDraft, Any>(

            save = { listOf(it.providerId, it.key, it.baseUrl, it.model, it.pasteNote, it.advancedOpen) },
            restore = {
                KeyDraft(
                    UserAiKey(
                        providerId = it[0] as String,
                        apiKey = it[1] as String,
                        baseUrl = it[2] as String,
                        model = it[3] as String,
                    ),
                ).apply {
                    pasteNote = it[4] as String
                    advancedOpen = it[5] as Boolean
                }
            },
        )
    }
}

@Composable
private fun DisclosureRow(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
    PortalRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        chevron = false,
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

@Composable
private fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { content() }
}

private const val AI_ICON = "ai"

@Composable
private fun SwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .portalCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Экран ключей для превью и тестов — из тех же чистых правил, что и в бою. */
fun aiKeysScreenOf(
    keys: UserAiKeys = UserAiKeys.NONE,
    builtIn: Set<String> = emptySet(),
    facts: Map<String, AiFact> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): AiKeysScreen = AiKeysScreen(
    keys = keys,
    services = aiServiceLines(keys, builtIn, facts, now),
    checkedLine = aiCheckedLine(facts, now),
)

private val previewProvider: AiProvider = AI_PROVIDERS.first()

@Preview(name = "Настройки · список разделов (#563)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsList() = PointTheme(darkTheme = true) {

    KeyScreen(
        screen = aiKeysScreenOf(
            keys = UserAiKeys.NONE.with(UserAiKey(previewProvider.id, "sk-or-v1-9c2f4d7ab31e")),
            builtIn = setOf("groq", "mistral"),
        ),
        onSave = {},
        onCancel = {},
        soundEnabled = true,
        cloudEnabled = true,
    )
}

@Preview(name = "Ключи AI · все сервисы списком (#699)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeysAllServices() = PointTheme(darkTheme = true) {

    val now = System.currentTimeMillis()
    KeyScreen(
        screen = aiKeysScreenOf(
            keys = UserAiKeys.NONE.with(UserAiKey(previewProvider.id, "sk-or-v1-9c2f4d7ab31e")),
            builtIn = setOf("groq", "mistral", "gemini"),
            facts = mapOf(
                previewProvider.id to AiFact(AiOutcome.ANSWERED, now - 3 * 60_000),
                "groq" to AiFact(AiOutcome.LIMIT, now - 26 * 60 * 60_000L),
                "mistral" to AiFact(AiOutcome.BAD_KEY, now - 5 * 60 * 60_000L),
            ),
            now = now,
        ),
        note = "AI недоступен — задайте свой ключ",
        onSave = {},
        onCancel = {},
    )
}

@Preview(name = "Ключи AI · проверка идёт (#699)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeysChecking() = PointTheme(darkTheme = true) {
    KeyScreen(
        screen = aiKeysScreenOf(builtIn = setOf("groq")),
        onSave = {},
        onCancel = {},
        checking = CHECK_ALL_SERVICES,
    )
}

@Preview(name = "Ключи AI · пришли с действия (#699)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeysErrand() = PointTheme(darkTheme = true) {

    KeyScreen(
        screen = aiKeysScreenOf(keys = UserAiKeys.NONE.with(UserAiKey(OWN_SERVICE_ID, "ключ", baseUrl = "https://мой/v1"))),
        onSave = {},
        onCancel = {},
        errand = KeyErrand(action = "Понять", objectName = "чек.jpg"),
        verdict = KeyVerdict.Works("Готово"),
        verdictFor = OWN_SERVICE_ID,
    )
}
