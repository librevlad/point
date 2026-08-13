package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiProvider
import com.point.core.flow.KeyVerdict
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyVerdict
import com.point.core.flow.looksLikeApiKey
import com.point.desktop.PcConfig
import kotlinx.coroutines.launch

/**
 * Куда человек зашёл внутри настроек (#886).
 *
 * Раньше настройки компьютера были одним полотном длиной в три с половиной окна: круг
 * устройств, имя компьютера, одиннадцать сервисов AI, три поля ключей, данные, интеграции.
 * Тот, кто пришёл переименовать компьютер, прокручивал мимо чужой ему инфраструктуры.
 * Разделы те же, что на телефоне, и открываются так же — строкой.
 */
enum class SettingsPage(val title: String) {
    ROOT("Настройки"),
    DEVICES("Мои устройства"),
    KEYS("Ключи AI"),
    PRIVACY("Отправка и приватность"),
    DATA("Что Point помнит"),
}

/** Корень настроек: пять разделов, за строками — свои экраны. */
@Composable
fun SettingsRoot(
    config: PcConfig,
    devices: Int,
    email: String,
    cloudAllowed: Boolean,
    onSave: (PcConfig) -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    var rightClick by remember { mutableStateOf(config.rightClick) }
    var sound by remember { mutableStateOf(config.sound) }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Порядок и слова разделов — те же, что на телефоне (#881).
        Section("Аккаунт и устройства") {
            PortalRow(
                title = "Мои устройства",
                subtitle = devicesLine(email, devices),
                onClick = { onOpen(SettingsPage.DEVICES) },
            )
        }

        Section("AI и приватность") {
            PortalRow(
                title = "Ключи AI",
                subtitle = keysLine(config),
                onClick = { onOpen(SettingsPage.KEYS) },
            )
            // Согласие на облако компьютер спрашивал и запоминал, но показать его было
            // негде — и забрать обратно тоже. Разрешение без выхода из него нарушает
            // §11: объект уходит с устройства только по живому согласию (#886).
            PortalRow(
                title = "Отправка и приватность",
                subtitle = cloudLine(cloudAllowed) + " · " + config.privacy.title,
                onClick = { onOpen(SettingsPage.PRIVACY) },
            )
        }

        // Звук на компьютере играет и приезжает с телефона, но выключателя здесь не было:
        // поменять настройку можно было только с той стороны (#886).
        Section("Поведение Point") {
            SwitchRow(
                title = "Звук действий",
                subtitle = "Тихий отклик, когда объект приезжает с телефона.",
                on = sound,
            ) {
                sound = !sound
                onSave(config.copy(sound = sound))
            }
        }

        Section("Данные") {
            PortalRow(
                title = "Что Point помнит",
                subtitle = "Убирается через сутки · перетащенный файл не трогается",
                onClick = { onOpen(SettingsPage.DATA) },
            )
        }

        // «Показывать · выключить» читалось как загадка: состояние это или действие (#878).
        // Переключатель говорит состояние словом, а нажатие меняет его.
        Section("Интеграции") {
            SwitchRow(
                title = "«Открыть в Point» в меню файла",
                subtitle = if (rightClick) "Показывается" else "Не показывается",
                on = rightClick,
            ) {
                rightClick = !rightClick
                onSave(config.copy(rightClick = rightClick))
            }
        }

        // Версия видна человеку, а не только в свойствах файла (#822): падение из-за старой
        // установки перестаёт быть загадкой — «у меня от шестого августа» видно сразу.
        // Номер один на оба устройства: телефон говорил 0.3.0, компьютер — 3.0.0 (#886).
        Text(
            "Point ${com.point.desktop.BuildInfo.VERSION} · сборка ${com.point.desktop.BuildInfo.BUILT_ON}",
            style = PointType.small.copy(color = PointColors.muted),
        )
    }
}

internal fun devicesLine(email: String, devices: Int): String {
    val count = when {
        devices <= 0 -> "устройств пока нет"
        devices == 1 -> "одно устройство"
        devices in 2..4 -> "$devices устройства"
        else -> "$devices устройств"
    }
    return listOf(email, count).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Счёт своих ключей — та же строка, что на телефоне (#888). */
internal fun keysLine(config: PcConfig): String =
    com.point.core.flow.aiKeysSummary(config.aiKeys)

/** Экран круга устройств: сюда же переехало имя компьютера — его видят другие устройства. */
@Composable
fun SettingsDevices(
    config: PcConfig,
    onSave: (PcConfig) -> Unit,
    devicesPane: @Composable () -> Unit,
) {
    var name by remember { mutableStateOf(config.name) }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        devicesPane()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ИМЯ ЭТОГО КОМПЬЮТЕРА", style = PointType.label)
            Field(
                value = name,
                onChange = { name = it; onSave(config.copy(name = it.trim().ifBlank { config.name })) },
                hint = "Так его видят ваши другие устройства",
            )
        }
    }
}

internal fun cloudLine(allowed: Boolean): String =
    if (allowed) "Облако разрешено" else "Облако спрашивается каждый раз"

/**
 * Экран отправки: что уже разрешено и как это забрать.
 *
 * Компьютер знает про облако ровно одно разрешение — показывать объект моделям. Про открытую
 * ссылку он спрашивает каждый раз и не запоминает ответ, и здесь об этом сказано прямо,
 * а не умолчанием.
 */
@Composable
fun SettingsPrivacy(
    allowed: Boolean,
    level: com.point.core.flow.PrivacyLevel,
    onRevoke: () -> Unit,
    onPickLevel: (com.point.core.flow.PrivacyLevel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Те же три режима и те же слова, что на телефоне (#893). Раньше выбор человека
        // сюда просто не доезжал: «только на этом устройстве» на компьютере не значило
        // ничего, и запись всё равно уходила на чужой сервер.
        Text(com.point.core.flow.PRIVACY_SETTING_TITLE.uppercase(), style = PointType.label)
        Text(com.point.core.flow.PRIVACY_SETTING_HINT, style = PointType.small)
        com.point.core.flow.PrivacyLevel.entries.forEach { it ->
            PortalRow(
                title = it.title,
                subtitle = it.what,
                onClick = { onPickLevel(it) },
                primary = it == level,
                // Выбор, а не переход: шеврон обещал бы следующий экран, которого нет.
                // Разницу между режимами надо прочитать целиком — она про приватность.
                chevron = false,
                subtitleMaxLines = 4,
            )
        }

        Text("Показывать объект моделям", style = PointType.body)
        Text(
            if (allowed) {
                "Разрешено. Point показывает объект сервису, когда вы нажали действие, " +
                    "которое без этого не работает. Заберите разрешение — и он спросит заново."
            } else {
                "Пока не разрешено. Point спросит перед первым действием, которому нужен сервис."
            },
            style = PointType.small,
        )
        if (allowed) Action("Забрать разрешение", onRevoke)

        Text("Выложить по открытой ссылке", style = PointType.body)
        Text(
            "Про такое Point спрашивает каждый раз и ответ не запоминает: ссылка на сутки — " +
                "каждый раз новая ставка.",
            style = PointType.small,
        )
    }
}

/** Экран данных: что лежит и как это убрать. */
@Composable
fun SettingsData(swept: Int?, onSweepNow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Point не хранит дольше суток: присланное с телефона и сделанное здесь убирается " +
                "само. Файл, который вы перетащили мышью, не трогается никогда.",
            style = PointType.small,
        )
        Action(swept?.let { "Убрано: $it" } ?: "Убрать прямо сейчас", onSweepNow)
    }
}

/**
 * Экран ключей: те же группы и та же очередь, что на телефоне (#887, #888).
 *
 * Раньше здесь выбирался ОДИН сервис и к нему одно поле ключа — модель, которой нет на
 * телефоне. Теперь у каждого сервиса свой ключ, и связка целиком ездит между устройствами.
 */
@Composable
fun SettingsKeys(
    config: PcConfig,
    onSave: (PcConfig) -> Unit,
    keyCheck: com.point.core.flow.AiKeyCheck =
        com.point.core.flow.HttpAiKeyCheck(com.point.core.flow.UrlConnectionHttpJson()),
    onOpenUrl: (String) -> Unit = {},
) {
    var keys by remember { mutableStateOf(config.aiKeys) }
    var speechKey by remember { mutableStateOf(config.speech.key) }
    var ocrKey by remember { mutableStateOf(config.ocr.key) }
    var open by remember { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf<KeyVerdict?>(null) }
    var verdictFor by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun store(next: com.point.core.flow.UserAiKeys = keys) {
        keys = next
        onSave(
            config.copy(
                aiKeys = next,
                speech = config.speech.copy(key = speechKey.trim()),
                ocr = config.ocr.copy(key = ocrKey.trim()),
            ),
        )
    }

    // Своих ключей у Point на компьютере нет: здесь работает то, к чему вписан ваш ключ.
    val lines = com.point.core.flow.aiServiceLines(keys, emptySet(), emptyMap(), 0L)

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Панель состояния, а не документация (#902): наверху две мысли, подробности —
        // за «Как это работает».
        Text(com.point.core.flow.AI_CHAIN_WHAT, style = PointType.small)
        var howOpen by remember { mutableStateOf(false) }
        Text(
            if (howOpen) "Свернуть" else "Как это работает",
            style = PointType.small.copy(color = PointColors.cyan),
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { howOpen = !howOpen }
                .padding(vertical = 3.dp),
        )
        if (howOpen) {
            Text(
                com.point.core.flow.AI_CHAIN_MORE + " Ключи общие с телефоном: вписанный " +
                    "здесь появится там, и наоборот.",
                style = PointType.small.copy(color = PointColors.muted),
            )
        }
        Text(com.point.core.flow.aiKeysCount(keys), style = PointType.body)

        com.point.core.flow.aiServiceGroups(lines).forEach { (group, rows) ->
            Text(group.title.uppercase(), style = PointType.label)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { line ->
                    PortalRow(
                        // Номер — место в очереди обращения, то же, что на телефоне (#902).
                        title = if (line.place <= 0) line.name else "%02d  %s".format(line.place, line.name),
                        subtitle = when {
                            open == line.providerId -> line.what
                            else -> line.trouble
                        },
                        onClick = { open = if (open == line.providerId) null else line.providerId },
                    )
                    if (open == line.providerId) {
                        ServiceKey(
                            line = line,
                            saved = keys.of(line.providerId),
                            checking = checking == line.providerId,
                            verdict = verdict.takeIf { verdictFor == line.providerId },
                            onOpenUrl = onOpenUrl,
                            onSave = { key -> verdict = null; store(keys.with(key)) },
                            onForget = { store(keys.without(line.providerId)) },
                            onCheck = { key ->
                                if (checking == null && looksLikeApiKey(key.apiKey)) {
                                    checking = line.providerId
                                    scope.launch {
                                        verdict = keyVerdict(keyCheck.check(com.point.core.flow.aiCall(key)))
                                        verdictFor = line.providerId
                                        checking = null
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        Text("ДЛЯ ЗАПИСЕЙ И СНИМКОВ", style = PointType.label)
        Field(speechKey, { speechKey = it; store() }, "Ключ расшифровки речи", secret = true)
        Field(ocrKey, { ocrKey = it; store() }, "Ключ чтения снимков — необязателен", secret = true)
    }
}

/** Что открывается под сервисом: сайт, поле ключа, проверка. */
@Composable
private fun ServiceKey(
    line: com.point.core.flow.AiServiceLine,
    saved: com.point.core.flow.UserAiKey?,
    checking: Boolean,
    verdict: KeyVerdict?,
    onOpenUrl: (String) -> Unit,
    onSave: (com.point.core.flow.UserAiKey) -> Unit,
    onForget: () -> Unit,
    onCheck: (com.point.core.flow.UserAiKey) -> Unit,
) {
    val provider = AI_PROVIDERS.firstOrNull { it.id == line.providerId }
    var draft by remember(line.providerId, saved) { mutableStateOf(saved?.apiKey.orEmpty()) }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PointColors.surfaceDeep)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        provider?.freeNote?.let { Text(it, style = PointType.small.copy(color = PointColors.cyan)) }
        provider?.let { Action("Открыть сайт ${it.name}") { onOpenUrl(it.keyUrl) } }

        Field(draft, { draft = it }, "Ключ ${line.name}", secret = true)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (checking) "Проверяю…" else "Сохранить и проверить") {
                val key = com.point.core.flow.UserAiKey(
                    providerId = line.providerId,
                    apiKey = draft.trim(),
                    savedAt = saved?.savedAt ?: 0L,
                )
                onSave(key)
                onCheck(key)
            }
            if (saved != null) Action("Забыть ключ", onForget)
        }
        verdict?.let { Verdict(it) }
    }
}

@Composable
private fun Verdict(verdict: KeyVerdict) {
    when (verdict) {
        is KeyVerdict.Works -> Text("Ключ работает: " + verdict.reply, style = PointType.small)
        is KeyVerdict.Refused -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(verdict.what, style = PointType.body)
            Text(verdict.fix, style = PointType.small.copy(color = PointColors.muted))
        }
    }
}

/**
 * Раздел настроек: мелкая метка капсом, как на телефоне.
 *
 * Раньше здесь стоял заголовок кеглем в 20 px — раздел выглядел заголовком статьи и спорил
 * с самими настройками за внимание (#886).
 */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = PointType.label)
        content()
    }
}

/** Строка-переключатель: состояние сказано словом под названием и видно справа. */
@Composable
private fun SwitchRow(title: String, subtitle: String, on: Boolean, onToggle: () -> Unit) {
    PortalRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        trailing = {
            Box(
                modifier = Modifier.width(40.dp).height(23.dp)
                    .clip(CircleShape)
                    .background(if (on) PointColors.violet else PointColors.surfaceDeep)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier.padding(horizontal = 3.dp).size(17.dp).clip(CircleShape)
                        .background(if (on) Color.White else PointColors.muted),
                )
            }
        },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hint, style = PointType.small.copy(color = PointColors.muted))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PointType.body,
            cursorBrush = SolidColor(PointColors.violet),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PointColors.surfaceDeep)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun Action(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(title, style = PointType.body)
    }
}
