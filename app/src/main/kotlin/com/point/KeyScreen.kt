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

    tileAdded: Boolean = false,
    memory: com.point.core.flow.HistoryFootprint? = null,
    onForgetAll: () -> Unit = {},
    version: String = "",
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
                    tileAdded = tileAdded,
                    memory = memory,
                    version = version,
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

                SettingsSection.ENTRIES -> EntriesSection(
                    tileAdded = tileAdded,
                    onBack = { section = null },
                )

                SettingsSection.MEMORY -> MemorySection(
                    memory = memory,
                    onForgetAll = onForgetAll,
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

private enum class SettingsSection { KEY, PRIVACY, APP, ENTRIES, MEMORY }

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
    tileAdded: Boolean,
    memory: com.point.core.flow.HistoryFootprint?,
    version: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {

        ScreenHeader(title = SETTINGS_TITLE, modifier = Modifier.padding(bottom = 2.dp))

        // Порядок разделов один на телефон и компьютер (#881): аккаунт и устройства →
        // AI и приватность → поведение → данные → интеграции. «Мои устройства» стоит первым
        // не по важности настройки, а потому что это состояние: вошёл ли человек и видят ли
        // его устройства друг друга — первый вопрос, с которым сюда приходят.
        SettingsGroup(ACCOUNT_GROUP_TITLE) {
            SettingsRow(
                title = MY_DEVICES_TITLE,
                subtitle = "Вход, круг устройств и выход.",
                onClick = onOpenDevices,
                appearIndex = 0,
            )
        }

        // Ключи и приватность — одна область: кто читает объект и что ему позволено.
        // Раньше они стояли соседними строками в разделе «AI и облако» (#881).
        SettingsGroup(AI_GROUP_TITLE) {
            SettingsRow(
                title = KEY_SECTION_TITLE,
                subtitle = keyLine,
                onClick = { onOpen(SettingsSection.KEY) },
                appearIndex = 1,
            )
            GroupSeam()
            SettingsRow(
                title = PRIVACY_SECTION_TITLE,

                subtitle = (if (cloudEnabled) "Облако разрешено" else "Облако выключено") +
                    " · ${privacyLevel.title}" + (if (yoloEnabled) " · $YOLO_TITLE" else ""),
                onClick = { onOpen(SettingsSection.PRIVACY) },
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

        // Копии объектов лежат на диске молча — здесь видно сколько и как забыть (#821).
        SettingsGroup(DATA_GROUP_TITLE) {
            SettingsRow(
                title = MEMORY_TITLE,
                subtitle = memoryLine(memory),
                onClick = { onOpen(SettingsSection.MEMORY) },
                appearIndex = 4,
            )
        }

        // Точки входа — это способы запустить Point из системы, а не настройка приложения.
        SettingsGroup(INTEGRATIONS_GROUP_TITLE) {
            SettingsRow(
                title = ENTRIES_TITLE,
                subtitle = entriesLine(tileAdded),
                onClick = { onOpen(SettingsSection.ENTRIES) },
                appearIndex = 5,
            )
        }

        if (version.isNotBlank()) {
            Text(
                "Point $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
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
internal fun BackToList(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("← $SETTINGS_TITLE", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val AI_GROUP_TITLE = "AI и приватность"
private const val ACCOUNT_GROUP_TITLE = "Аккаунт и устройства"

internal const val KEY_SECTION_TITLE = "Ключи AI"
internal const val PRIVACY_SECTION_TITLE = "Отправка и приватность"
internal const val APP_SECTION_TITLE = "Поведение Point"

private const val DATA_GROUP_TITLE = "Данные"

private const val INTEGRATIONS_GROUP_TITLE = "Интеграции"
internal const val SOUND_TITLE = "Звук действий"

internal const val ENTRIES_TITLE = "Точки входа"
internal const val MEMORY_TITLE = "Что Point помнит"


internal const val SHARE_ENTRY = "Системное «Поделиться» — Point принимает объект из любого приложения."
internal const val TILE_ENTRY_ON = "Плитка в шторке — Point открывается одним касанием сверху."
internal const val TILE_ENTRY_OFF = "Плитку в шторке можно добавить из «Нового объекта»."

internal const val MEMORY_WHAT =
    "Point держит последние ${com.point.core.flow.HistoryFootprint.KEPT} объектов — копии " +
        "лежат на телефоне, чтобы «Недавнее» открывалось без исходника. Старое забывается само."

internal const val DROP_LINKS_LIVE = "Ссылки, которыми вы делились через сервер, перестают действовать через сутки."

internal const val FORGET_ALL = "Забыть всё"

internal fun entriesLine(tileAdded: Boolean): String =
    if (tileAdded) "Системное «Поделиться» и плитка в шторке" else "Системное «Поделиться»"

internal fun memoryLine(memory: com.point.core.flow.HistoryFootprint?): String = when {
    memory == null -> MEMORY_TITLE_UNKNOWN
    memory.count == 0 -> "Пока ничего не сохранено"
    else -> "Объектов: ${memory.count} · ${com.point.core.flow.humanWeight(memory.bytes) ?: NOTHING_KEPT}"
}

internal const val NOTHING_KEPT = "пусто"

internal const val MEMORY_TITLE_UNKNOWN = "Сколько занято — сейчас посчитаем"


internal class KeyDraft(saved: UserAiKey) {
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
internal fun DisclosureRow(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
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
internal fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { content() }
}

internal const val AI_ICON = "ai"

@Composable
internal fun SwitchCard(
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
