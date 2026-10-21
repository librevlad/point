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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFact
import com.point.core.flow.AiOutcome
import com.point.core.flow.AiProvider
import com.point.core.flow.KEY_SECTION_TITLE
import com.point.core.flow.KeyVerdict
import com.point.core.flow.MEMORY_TITLE
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.OWN_SERVICE_ID
import com.point.core.flow.PRIVACY_SECTION_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.YOLO_TITLE
import com.point.core.flow.accountIn
import com.point.core.flow.aiChainLine
import com.point.core.flow.aiKeysSummary
import com.point.core.flow.aiServiceLines
import com.point.core.flow.urlWithAccount
import com.point.core.model.tr
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme
import com.point.core.ui.ScreenTopBar
import com.point.core.ui.GroupHeader
import com.point.core.ui.GlassGroup
import com.point.core.ui.glassSwitchColors

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

    val aboutKeys = note != null || errand != null || checking != null || verdict != null
    var section by rememberSaveable { mutableStateOf(if (aboutKeys) SettingsSection.KEY else null) }

    // Проверка, отказ или поручение ведут в раздел ключей; их конец назад не выкидывает.
    // Прежде раздел пересоздавался на каждую смену приговора, и «Удалить ключ» после проверки
    // выбрасывал человека в корень настроек.
    LaunchedEffect(aboutKeys) { if (aboutKeys) section = SettingsSection.KEY }

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

                // «Назад» у корня настроек ведёт туда, откуда их открыли, — к вещи или в «Недавнее».
                null -> ScreenTopBar(title = SETTINGS_TITLE, onBack = onCancel, backLabel = LEAVE_BACK)
                else -> Unit
            }
            when (section) {
                null -> SettingsList(
                    keyLine = aiKeysSummary(screen.keys),
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
                    privacyLevel = privacyLevel,
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
                if (verdict is KeyVerdict.Works) tr("Готово") else tr("Отмена"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class SettingsSection { KEY, PRIVACY, APP, ENTRIES, MEMORY }

@Composable
private fun SettingsList(
    keyLine: String,
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
    Column {

        // Порядок разделов один на телефон и компьютер (#881): аккаунт и устройства →
        // AI и приватность → поведение → данные → интеграции. «Мои устройства» стоит первым
        // не по важности настройки, а потому что это состояние: вошёл ли человек и видят ли
        // его устройства друг друга — первый вопрос, с которым сюда приходят.
        SettingsGroup(ACCOUNT_GROUP_TITLE) {
            SettingsRow(
                title = MY_DEVICES_TITLE,
                subtitle = tr("Вход, круг устройств и выход."),
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

                // Сводка называет выбранный уровень и не оправдывается состоянием тумблера:
                // «Облако разрешено · Только на этом устройстве» читалось противоречием (#1003).
                subtitle = privacyLevel.title + (if (yoloEnabled) " · $YOLO_TITLE" else ""),
                onClick = { onOpen(SettingsSection.PRIVACY) },
                appearIndex = 2,
            )
        }

        SettingsGroup(APP_SECTION_TITLE) {
            SettingsRow(
                title = SOUND_TITLE,
                subtitle = tr("Тихий фирменный отклик на каждое действие."),
                onClick = { onOpen(SettingsSection.APP) },
                appearIndex = 3,
                trailing = { Switch(checked = soundEnabled, onCheckedChange = onToggleSound, colors = glassSwitchColors()) },
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
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.point.core.ui.PointGlyph(size = 14.dp)
                Text("Point $version", style = com.point.core.ui.theme.PointText.note)
            }
        }
    }
}

/**
 * Группа настроек — заголовок над панелью и строки в ней (макет «Настройки · телефон»): порядок
 * разделов читается по заголовкам, строки раздела — одной панелью стекла.
 */
@Composable
private fun SettingsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    GroupHeader(title)
    GlassGroup(content = rows)
}

/** Линия между строками настроек: плашек у них нет, и линия идёт от текста. */
@Composable
private fun GroupSeam() {
    com.point.core.ui.GroupSeam(start = 14.dp)
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

/**
 * Шапка раздела настроек: круглая кнопка назад к списку и имя раздела рядом (макет «Мои
 * устройства · телефон»). Куда ведёт кнопка, говорит её имя — «Настройки».
 */
@Composable
internal fun SectionTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenTopBar(title = title, onBack = onBack, backLabel = SETTINGS_TITLE, modifier = modifier)
}

private val AI_GROUP_TITLE: String get() = tr("AI и приватность")
private val ACCOUNT_GROUP_TITLE: String get() = tr("Аккаунт и устройства")

// Имена разделов «Ключи AI», «Отправка и приватность» и «Что Point помнит» переехали в
// :core:flow к SETTINGS_TITLE (#1249): те же разделы открываются в окне компьютера, и слово
// у них теперь одно на оба устройства.
internal val APP_SECTION_TITLE: String get() = tr("Поведение Point")

private val DATA_GROUP_TITLE: String get() = tr("Данные")

private val INTEGRATIONS_GROUP_TITLE: String get() = tr("Интеграции")
internal val SOUND_TITLE: String get() = tr("Звук действий")

internal val ENTRIES_TITLE: String get() = tr("Точки входа")


internal val SHARE_ENTRY: String get() = tr("Системное «Поделиться» — Point принимает объект из любого приложения.")
internal val TILE_ENTRY_ON: String get() = tr("Плитка в шторке — Point открывается одним касанием сверху.")
internal val TILE_ENTRY_OFF: String get() = tr("Плитку в шторке можно добавить из «Нового объекта».")

internal const val MEMORY_WHAT =
    tr("Point держит последние {0} объектов — копии " +
        "лежат на телефоне, чтобы «Недавнее» открывалось без исходника. Старое забывается само.", com.point.core.flow.HistoryFootprint.KEPT)

internal val DROP_LINKS_LIVE: String get() = tr("Ссылки, которыми вы делились через сервер, перестают действовать через сутки.")

internal val FORGET_ALL: String get() = tr("Забыть всё")

internal fun entriesLine(tileAdded: Boolean): String =
    if (tileAdded) tr("Системное «Поделиться» и плитка в шторке") else tr("Системное «Поделиться»")

internal fun memoryLine(memory: com.point.core.flow.HistoryFootprint?): String = when {
    memory == null -> MEMORY_TITLE_UNKNOWN
    memory.count == 0 -> tr("Пока ничего не сохранено")
    else -> tr("Объектов: {0} · {1}", memory.count, com.point.core.flow.humanWeight(memory.bytes) ?: NOTHING_KEPT)
}

internal val NOTHING_KEPT: String get() = tr("пусто")

internal val MEMORY_TITLE_UNKNOWN: String get() = tr("Сколько занято — сейчас посчитаем")


internal class KeyDraft(saved: UserAiKey) {
    var providerId by mutableStateOf(saved.providerId)
    var key by mutableStateOf(saved.apiKey)
    var model by mutableStateOf(saved.model)
    var baseUrl by mutableStateOf(saved.baseUrl)

    /** Номер аккаунта — у сервиса, чей адрес из него собирается; хранится адресом. */
    var account by mutableStateOf(provider()?.accountIn(saved.baseUrl).orEmpty())

    var pasteNote by mutableStateOf("")

    var advancedOpen by mutableStateOf(false)

    /** Показан ли ключ буквами, а не точками. */
    var revealed by mutableStateOf(false)

    /** Сервис, чей это ключ по его началу, если не этот. */
    var elsewhere by mutableStateOf("")

    private fun provider() = AI_PROVIDERS.firstOrNull { it.id == providerId }

    fun entered(): UserAiKey {
        val withAccount = provider()?.takeIf { it.account != null && account.isNotBlank() }
        return UserAiKey(
            providerId,
            key.trim(),
            model.trim(),
            withAccount?.urlWithAccount(account) ?: baseUrl.trim(),
        )
    }

    companion object {
        val Saver = listSaver<KeyDraft, Any>(

            save = {
                listOf(
                    it.providerId, it.key, it.baseUrl, it.model, it.pasteNote, it.advancedOpen,
                    it.account, it.revealed, it.elsewhere,
                )
            },
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
                    account = it[6] as String
                    revealed = it[7] as Boolean
                    elsewhere = it[8] as String
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
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = com.point.core.ui.theme.PointText.rowTitle)
            Text(
                description,
                style = com.point.core.ui.theme.PointText.note,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = glassSwitchColors())
    }
}

/** Экран ключей для превью и тестов — из тех же чистых правил, что и в бою. */
fun aiKeysScreenOf(
    keys: UserAiKeys = UserAiKeys.NONE,
    builtIn: Set<String> = emptySet(),
    facts: Map<String, AiFact> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): AiKeysScreen {
    val services = aiServiceLines(keys, builtIn, facts, now)
    return AiKeysScreen(keys = keys, services = services, checkedLine = aiChainLine(services, facts, now))
}

private val previewProvider: AiProvider = AI_PROVIDERS.first()

@Preview(name = "Настройки · список разделов (#563)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Ключи AI · все сервисы списком (#699)", showBackground = true, backgroundColor = 0xFF2B2531)
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

@Preview(name = "Ключи AI · проверка идёт (#699)", showBackground = true, backgroundColor = 0xFF2B2531)
@Composable
private fun PreviewKeysChecking() = PointTheme(darkTheme = true) {
    KeyScreen(
        screen = aiKeysScreenOf(builtIn = setOf("groq")),
        onSave = {},
        onCancel = {},
        checking = CHECK_ALL_SERVICES,
    )
}

@Preview(name = "Ключи AI · пришли с действия (#699)", showBackground = true, backgroundColor = 0xFF2B2531)
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
