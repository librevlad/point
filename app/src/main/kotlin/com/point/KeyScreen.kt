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
import com.point.core.flow.AiProvider
import com.point.core.flow.KeyVerdict
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keySetLabel
import com.point.core.flow.looksLikeApiKey
import com.point.core.flow.providerForBaseUrl
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
    config: UserAiConfig,

    note: String? = null,

    errand: KeyErrand? = null,
    onSave: (UserAiConfig) -> Unit,
    onCancel: () -> Unit,
    usageEnabled: Boolean,
    usageSummary: UsageSummary?,
    onToggleUsage: (Boolean) -> Unit,

    checking: Boolean = false,

    verdict: KeyVerdict? = null,

    onCheck: (UserAiConfig) -> Unit = {},

    onPasteKey: () -> String? = { null },

    onForgetKey: () -> Unit = {},
    soundEnabled: Boolean = true,
    onToggleSound: (Boolean) -> Unit = {},

    privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit = {},

    cloudEnabled: Boolean = false,
    onToggleCloud: (Boolean) -> Unit = {},

    onOpenUrl: (String) -> Unit = {},

    onOpenDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    val draft = rememberSaveable(config, saver = KeyDraft.Saver) { KeyDraft(config) }

    var section by rememberSaveable(note, errand, checking, verdict) {
        mutableStateOf(
            if (note != null || errand != null || checking || verdict != null) SettingsSection.KEY else null,
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
                    keyLine = keySetLabel(draft.key, saved = draft.savedIn(config)),
                    cloudEnabled = cloudEnabled,
                    privacyLevel = privacyLevel,
                    soundEnabled = soundEnabled,
                    onToggleSound = onToggleSound,
                    usageEnabled = usageEnabled,
                    onToggleUsage = onToggleUsage,
                    onOpen = { section = it },
                    onOpenDevices = onOpenDevices,
                )

                SettingsSection.KEY -> KeySection(
                    config = config,
                    draft = draft,
                    note = note,
                    errand = errand,
                    checking = checking,
                    verdict = verdict,
                    onCheck = onCheck,
                    onPasteKey = onPasteKey,
                    onForgetKey = onForgetKey,
                    onOpenUrl = onOpenUrl,
                    onBack = { section = null },

                    onLeave = onCancel,
                )

                SettingsSection.PRIVACY -> PrivacySection(
                    cloudEnabled = cloudEnabled,
                    onToggleCloud = onToggleCloud,
                    privacyLevel = privacyLevel,
                    onPickPrivacyLevel = onPickPrivacyLevel,
                    onBack = { section = null },
                )

                SettingsSection.APP -> AppSection(
                    soundEnabled = soundEnabled,
                    onToggleSound = onToggleSound,
                    usageEnabled = usageEnabled,
                    usageSummary = usageSummary,
                    onToggleUsage = onToggleUsage,
                    onBack = { section = null },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (section == SettingsSection.KEY && draft.key.isNotBlank() && verdict !is KeyVerdict.Works) {
            TextButton(onClick = { onSave(draft.entered()) }) {
                Text("Сохранить без проверки", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
    soundEnabled: Boolean,
    onToggleSound: (Boolean) -> Unit,
    usageEnabled: Boolean,
    onToggleUsage: (Boolean) -> Unit,
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
                    " · ${privacyLevel.title}",
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
            GroupSeam()
            SettingsRow(
                title = USAGE_TITLE,
                subtitle = "Обезличенно, только на устройстве",
                onClick = { onOpen(SettingsSection.APP) },
                appearIndex = 4,
                trailing = { Switch(checked = usageEnabled, onCheckedChange = onToggleUsage) },
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

@Composable
private fun KeySection(
    config: UserAiConfig,
    draft: KeyDraft,
    note: String?,
    errand: KeyErrand?,
    checking: Boolean,
    verdict: KeyVerdict?,
    onCheck: (UserAiConfig) -> Unit,
    onPasteKey: () -> String?,
    onForgetKey: () -> Unit,
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

    val chosen = providerForBaseUrl(draft.baseUrl)
    SectionLabel("Шаг 1 · Откуда взять ключ")

    Text(
        "$AI_KEY_WHY Point работает на вашем ключе и вашей квоте — чужие ключи он не " +
            "хранит и не просит.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PortalRow(
        title = if (chosen != null) "Открыть сайт ${chosen.name}" else "Сначала выберите сервис",
        subtitle = if (chosen != null) {
            "Там выдают ключ: заведите аккаунт, скопируйте ключ — и вернитесь сюда. Откроется браузер."
        } else {
            "Ключ выдаёт сервис — выберите его строкой ниже, и сюда встанет ссылка на его страницу."
        },
        onClick = { if (chosen != null) onOpenUrl(chosen.keyUrl) else draft.servicesOpen = true },
        icon = bubbleIcon("open"),
        accent = bubbleColor("open"),
        chevron = false,
        subtitleMaxLines = 3,
    )

    DisclosureRow(
        title = "Сервис",
        subtitle = chosen?.let { "${it.name} · ${it.what}" } ?: "не выбран",
        open = draft.servicesOpen,
        onToggle = { draft.servicesOpen = !draft.servicesOpen },
    )
    Reveal(draft.servicesOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            AI_PROVIDERS.forEachIndexed { index, provider ->
                ProviderRow(
                    provider = provider,
                    selected = chosen?.id == provider.id,
                    index = index,
                    onChoose = {

                        if (chosen?.id != provider.id) {
                            draft.key = ""
                            draft.pasteNote = ""
                        }
                        draft.baseUrl = provider.baseUrl
                        draft.model = provider.models.substringBefore(',')
                        draft.servicesOpen = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    SectionLabel("Шаг 2 · Вставьте ключ")
    OutlinedTextField(
        value = draft.key,
        onValueChange = {
            draft.key = it
            draft.pasteNote = ""
        },
        label = { Text("API-ключ") },
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

    OutcomeCard(
        title = keySetLabel(draft.key, saved = draft.savedIn(config)),
        outcome = Outcome.NONE,
        modifier = Modifier.fillMaxWidth(),
    )

    if (config.apiKey.isNotBlank()) {
        PortalRow(
            title = "Забыть ключ",
            subtitle = "Point сотрёт его с устройства. «Понять», «Перевести», «Спросить AI» " +
                "и расшифровка записи снова замолчат, пока не впишете новый.",
            onClick = {

                draft.key = ""
                draft.pasteNote = ""
                onForgetKey()
            },
            chevron = false,
            subtitleMaxLines = 3,
        )
    }

    DisclosureRow(
        title = "Модель и адрес",
        subtitle = listOf(draft.model, draft.baseUrl).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "подставятся вместе с сервисом" },
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

    Spacer(Modifier.height(6.dp))
    SectionLabel("Шаг 3 · Проверьте, что работает")
    Text(

        "Point спросит сервис одним коротким словом и покажет ответ. Ваш объект при этом " +
            "никуда не отправляется.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val canCheck = draft.key.isNotBlank() && !checking
    PortalRow(

        title = if (checking) "Проверяю…" else "Проверить и включить",
        onClick = { onCheck(draft.entered()) },
        icon = bubbleIcon(AI_ICON),

        primary = verdict !is KeyVerdict.Works,
        chevron = false,
        enabled = canCheck,
        modifier = Modifier.graphicsLayer { alpha = if (canCheck) 1f else 0.45f },
    )

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

    if (errand != null && verdict is KeyVerdict.Works) {
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
private fun PrivacySection(
    cloudEnabled: Boolean,
    onToggleCloud: (Boolean) -> Unit,
    privacyLevel: PrivacyLevel,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit,
    onBack: () -> Unit,
) {
    BackToList(onBack)
    ScreenHeader(title = PRIVACY_SECTION_TITLE, modifier = Modifier.padding(bottom = 9.dp))

    SwitchCard(
        title = "Отправка в облако",
        description = "Разрешает показывать объект моделям — по вашему тапу и с названием того, " +
            "куда он уедет. Выключите, и Point спросит заново. Выложить файл по открытой " +
            "ссылке этим тумблером нельзя: про такое спрашивают каждый раз.",
        checked = cloudEnabled,
        onCheckedChange = onToggleCloud,
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
    usageEnabled: Boolean,
    usageSummary: UsageSummary?,
    onToggleUsage: (Boolean) -> Unit,
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
    SwitchCard(
        title = USAGE_TITLE,
        description = "Обезличенно, только на устройстве — мерит, экономит ли Point переключения между приложениями.",
        checked = usageEnabled,
        onCheckedChange = onToggleUsage,

        tally = usageSummary
            ?.takeIf { usageEnabled }
            ?.let { "Объектов: ${it.objects} · действий: ${it.actions} · завершено в Point: ${it.completed}" },
    )
}

private const val AI_GROUP_TITLE = "AI и облако"
private const val ACCOUNT_GROUP_TITLE = "Аккаунт"

private const val KEY_SECTION_TITLE = "Ключ AI"
private const val PRIVACY_SECTION_TITLE = "Отправка и приватность"
private const val APP_SECTION_TITLE = "Приложение"
private const val SOUND_TITLE = "Звук действий"
private const val USAGE_TITLE = "Приватная статистика"

private class KeyDraft(config: UserAiConfig) {
    var key by mutableStateOf(config.apiKey)
    var model by mutableStateOf(config.model)
    var baseUrl by mutableStateOf(config.baseUrl)

    var pasteNote by mutableStateOf("")

    var servicesOpen by mutableStateOf(false)
    var advancedOpen by mutableStateOf(false)

    fun entered() = UserAiConfig(key.trim(), baseUrl.trim(), model.trim(), savedAt = System.currentTimeMillis())

    fun savedIn(config: UserAiConfig) = key.trim() == config.apiKey.trim() && key.isNotBlank()

    companion object {
        val Saver = listSaver<KeyDraft, Any>(

            save = { listOf(it.key, it.baseUrl, it.model, it.pasteNote, it.servicesOpen, it.advancedOpen) },
            restore = {
                KeyDraft(UserAiConfig(it[0] as String, it[1] as String, it[2] as String)).apply {
                    pasteNote = it[3] as String
                    servicesOpen = it[4] as Boolean
                    advancedOpen = it[5] as Boolean
                }
            },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: AiProvider,
    selected: Boolean,
    index: Int,
    onChoose: () -> Unit,
) {
    PortalRow(
        title = provider.name,
        subtitle = listOfNotNull(provider.what, provider.freeNote).joinToString(" · "),
        onClick = onChoose,
        icon = bubbleIcon(AI_ICON),
        accent = bubbleColor(AI_ICON),
        primary = selected,
        chevron = false,
        appearIndex = index,
        trailing = if (selected) {
            { Text("выбран", style = MaterialTheme.typography.labelMedium, color = Color.White) }
        } else {
            null
        },
    )
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
    tally: String? = null,
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
            if (tally != null) {
                Spacer(Modifier.height(6.dp))
                Text(tally, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(name = "Настройки · список разделов (#563)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsList() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig("sk-or-v1-9c2f4d7ab31e", AI_PROVIDERS.first().baseUrl, "google/gemma-4-31b-it:free"),
        onSave = {},
        onCancel = {},
        usageEnabled = true,
        usageSummary = UsageSummary(objects = 42, actions = 118, completed = 31),
        onToggleUsage = {},
        soundEnabled = true,
        cloudEnabled = true,
    )
}

@Preview(name = "Настройки · ключа ещё нет (#563)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsListEmpty() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig("", "", ""),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        soundEnabled = false,
        privacyLevel = PrivacyLevel.DEVICE_ONLY,
    )
}

@Preview(name = "Ключ AI · проверка сказала «работает» (#465)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenWorks() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig(apiKey = "sk-demo-ключ", baseUrl = AI_PROVIDERS.first().baseUrl, model = "gemma"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        verdict = KeyVerdict.Works("Готово"),
    )
}

@Preview(name = "Ключ AI · отказ говорит, что чинить (#465)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenRefused() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig(apiKey = "не-тот-ключ", baseUrl = AI_PROVIDERS[1].baseUrl, model = "llama"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        note = "AI недоступен — задайте свой ключ",
        verdict = KeyVerdict.Refused(
            what = "Ключ не подошёл",
            fix = "Скопируйте ключ целиком, без пробелов по краям, и проверьте, что он от того " +
                "сервиса, который выбран выше.",
        ),
    )
}

@Preview(name = "Ключ AI · проверка идёт (#465)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenChecking() = PointTheme(darkTheme = true) {
    KeyScreen(
        config = UserAiConfig(apiKey = "sk-demo-ключ", baseUrl = AI_PROVIDERS.first().baseUrl, model = "gemma"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        checking = true,
    )
}

@Preview(name = "Ключ AI · пришли с действия (#465)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenErrand() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig(apiKey = "", baseUrl = AI_PROVIDERS.first().baseUrl, model = "gemma"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        errand = KeyErrand(action = "Понять", objectName = "чек.jpg"),
    )
}

@Preview(name = "Ключ AI · путь кончился, объект ждёт (#465)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenErrandDone() = PointTheme(darkTheme = true) {

    KeyScreen(
        config = UserAiConfig(apiKey = "sk-demo-ключ", baseUrl = AI_PROVIDERS.first().baseUrl, model = "gemma"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        errand = KeyErrand(action = "Понять", objectName = "чек.jpg"),
        verdict = KeyVerdict.Works("Готово"),
    )
}
