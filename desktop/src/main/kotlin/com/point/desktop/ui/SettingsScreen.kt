package com.point.desktop.ui

import com.point.core.model.tr
import com.point.core.model.trPlural
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.point.core.flow.KEY_SECTION_TITLE
import com.point.core.flow.KeyVerdict
import com.point.core.flow.MEMORY_TITLE
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.PRIVACY_SECTION_TITLE
import com.point.core.flow.SETTINGS_TITLE
import com.point.desktop.PcConfig
import kotlinx.coroutines.launch

/**
 * Куда человек зашёл внутри настроек (#886).
 *
 * Раньше настройки компьютера были одним полотном длиной в три с половиной окна: круг
 * устройств, имя компьютера, одиннадцать сервисов AI, три поля ключей, данные, интеграции.
 * Тот, кто пришёл переименовать компьютер, прокручивал мимо чужой ему инфраструктуры.
 * Разделы те же, что на телефоне, и открываются так же — строкой.
 *
 * И зовутся теми же словами (#1249): имена берутся из общих констант, а не набираются здесь
 * заново. Набранные заново, они совпадали случайно — переименование в одном месте меняло имя
 * только в одном окне, и перешедший за компьютер искал раздел под старым названием.
 */
enum class SettingsPage(val title: String) {
    ROOT(SETTINGS_TITLE),
    DEVICES(MY_DEVICES_TITLE),
    KEYS(KEY_SECTION_TITLE),
    PRIVACY(PRIVACY_SECTION_TITLE),
    DATA(MEMORY_TITLE),
}

/** Корень настроек: пять разделов, за строками — свои экраны. */
@Composable
fun SettingsRoot(
    config: PcConfig,
    devices: Int,
    email: String,
    onSave: (PcConfig) -> Unit,

    /**
     * Поставить или снять пункт в меню файла Windows; ответ — по эффекту, встал ли; `null` —
     * прочитать, что вышло, не удалось (#1082).
     */
    onRightClick: suspend (Boolean) -> Boolean?,

    /** Стоит ли меню файла Windows в этом положении на деле; `null` — прочитать не удалось (#1082). */
    rightClickHolds: suspend (Boolean) -> Boolean?,
    onOpen: (SettingsPage) -> Unit,

    /** Вошёл ли человек: без входа строка устройств зовёт подключить их, а не считает пустоту. */
    signedIn: Boolean = email.isNotBlank(),
) {
    var rightClick by remember { mutableStateOf(config.rightClick) }
    var sound by remember { mutableStateOf(config.sound) }

    // Правда о пункте меню — факт, прочитанный при показе экрана и при тапе, а не память
    // последнего нажатия (#1082): после перезапуска включённый флаг не говорит «Показывается»
    // поверх пустого реестра. `null` — знания ещё нет, и подпись тогда молчит о вердикте:
    // «не исследовано» — это не «сбоя нет».
    var rightClickTrouble by remember { mutableStateOf<Boolean?>(null) }

    // Идёт ли чтение или работа прямо сейчас. Без этого «знания нет» звучало одинаково и пока
    // реестр читается, и когда прочитать его не вышло: слово «Проверяется» оставалось на экране
    // навсегда, хотя проверять было уже некому — повтора нет, чтение при входе одно (#1082).
    var rightClickChecking by remember { mutableStateOf(true) }

    // Номер вопроса: ответ на прежний вопрос не перебивает ответ на нынешний — ни чтение,
    // начатое до тапа, ни ответ первого из двух быстрых нажатий.
    var asked by remember { mutableStateOf(0) }
    val work = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val turn = asked
        val holds = rightClickHolds(rightClick)
        if (asked == turn) {
            rightClickTrouble = holds?.let { !it }
            rightClickChecking = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Порядок и слова разделов — те же, что на телефоне (#881).
        Section(tr("Аккаунт и устройства")) {
            PortalRow(
                title = MY_DEVICES_TITLE,
                subtitle = if (signedIn) devicesLine(email, devices) else com.point.desktop.NEEDS_SIGN_IN_FOR_DEVICES,
                onClick = { onOpen(SettingsPage.DEVICES) },
            )
        }

        Section(tr("AI и приватность")) {
            PortalRow(
                title = KEY_SECTION_TITLE,
                subtitle = keysLine(config),
                onClick = { onOpen(SettingsPage.KEYS) },
            )
            // Согласие на облако компьютер спрашивал и запоминал, но показать его было
            // негде — и забрать обратно тоже. Разрешение без выхода из него нарушает
            // §11: объект уходит с устройства только по живому согласию (#886).
            // Сводка называет выбранный уровень и не оправдывается состоянием согласия —
            // те же слова, что на телефоне (#1003); само согласие видно и отзывается внутри.
            PortalRow(
                title = PRIVACY_SECTION_TITLE,
                subtitle = config.privacy.title,
                onClick = { onOpen(SettingsPage.PRIVACY) },
            )
        }

        // Звук на компьютере играет и приезжает с телефона, но выключателя здесь не было:
        // поменять настройку можно было только с той стороны (#886).
        Section(tr("Поведение Point")) {
            SwitchRow(
                title = tr("Звук действий"),
                subtitle = tr("Тихий отклик, когда объект приезжает с телефона."),
                on = sound,
            ) {
                sound = !sound
                onSave(config.copy(sound = sound))
            }
        }

        Section(tr("Данные")) {
            PortalRow(
                title = MEMORY_TITLE,
                subtitle = tr("Убирается через сутки · перетащенный файл не трогается"),
                onClick = { onOpen(SettingsPage.DATA) },
            )
        }

        // «Показывать · выключить» читалось как загадка: состояние это или действие (#878).
        // Переключатель говорит состояние словом, а нажатие меняет его. Слово — правда:
        // когда запись в реестр не встала, включённый флаг не говорит «Показывается», а когда
        // не снялась — выключенный не говорит «Не показывается» (#1082).
        Section(tr("Интеграции")) {
            SwitchRow(
                title = tr("«Открыть в Point» в меню файла"),
                subtitle = rightClickLine(rightClick, rightClickTrouble, rightClickChecking),
                on = rightClick,
            ) {
                rightClick = !rightClick
                onSave(config.copy(rightClick = rightClick))

                // Реестр и ярлык — работа на секунды: на потоке кадров окно замирало бы на
                // каждом нажатии, а подпись всё это время утверждала бы исход, которого ещё
                // нет. Пока идёт — знания нет; ответ приходит и называет, что вышло (#1082).
                val turn = ++asked
                val wanted = rightClick
                rightClickTrouble = null
                rightClickChecking = true
                work.launch {
                    // Каждое нажатие делает то, что просило само, а не то, что стоит на экране
                    // к моменту его хода. Очередь на реестр и папку «Отправить» — у самого
                    // действия: экран уходит из композиции и возвращается с новой памятью, а
                    // начатая запись — нет, и такой уход её очередь обязана пережить (#1082).
                    val stood = onRightClick(wanted)
                    if (asked == turn) {
                        rightClickTrouble = stood?.let { !it }
                        rightClickChecking = false
                    }
                }
            }
        }

        // Версия видна человеку, а не только в свойствах файла (#822): падение из-за старой
        // установки перестаёт быть загадкой — «у меня от шестого августа» видно сразу.
        // Номер один на оба устройства: телефон говорил 0.3.0, компьютер — 3.0.0 (#886).
        Text(
            tr("Point {0} · сборка {1}", com.point.desktop.BuildInfo.VERSION, com.point.desktop.BuildInfo.BUILT_ON),
            style = PointType.mono,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * Подпись переключателя правой кнопки: сбой виден словом, а не прячется за флагом (#1082) —
 * и когда запись не встала, и когда не снялась.
 *
 * Вердикта нет в двух разных случаях, и звучат они по-разному. `checking` — реестр читается или
 * тап делает своё прямо сейчас: «Проверяется» тут правда. Прочитать не вышло — проверять уже
 * некому, и слово о длящемся действии осталось бы на экране навсегда: подпись называет это тем,
 * что оно есть. Сказать «Показывается», пока никто не смотрел в реестр, — та же неправда, что
 * прикрыть ею сбой, только короче.
 */
internal fun rightClickLine(on: Boolean, trouble: Boolean?, checking: Boolean): String = when {
    checking -> tr("Проверяется")
    trouble == null -> tr("Не удалось проверить — Windows не ответила")
    on && trouble -> tr("Не удалось включить — запись в меню файла не встала")
    on -> tr("Показывается")
    trouble -> tr("Не удалось выключить — пункт остался в меню файла")
    else -> tr("Не показывается")
}

internal fun devicesLine(email: String, devices: Int): String {
    val count = when {
        devices <= 0 -> tr("устройств пока нет")
        devices == 1 -> tr("одно устройство")
        else -> "$devices " + trPlural(devices, "устройство", "устройства", "устройств")
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        devicesPane()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("ИМЯ ЭТОГО КОМПЬЮТЕРА"), style = PointType.label)
            Field(
                value = name,
                onChange = { name = it; onSave(config.copy(name = it.trim().ifBlank { config.name })) },
                hint = tr("Так его видят ваши другие устройства"),
            )
        }
    }
}

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
    onOpenPolicy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Те же три режима и те же слова, что на телефоне (#893). Раньше выбор человека
        // сюда просто не доезжал: «только на этом устройстве» на компьютере не значило
        // ничего, и запись всё равно уходила на чужой сервер.
        Text(com.point.core.flow.PRIVACY_SETTING_TITLE.uppercase(), style = PointType.label)
        Text(com.point.core.flow.PRIVACY_SETTING_HINT, style = PointType.small)
        Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        }

        Text(tr("Показывать объект моделям"), style = PointType.body)
        Text(
            if (allowed) {
                tr("Разрешено. Point показывает объект сервису, когда вы нажали действие, " +
                    "которое без этого не работает. Заберите разрешение — и он спросит заново.")
            } else {
                tr("Пока не разрешено. Point спросит перед первым действием, которому нужен сервис.")
            },
            style = PointType.small,
        )
        if (allowed) Action(tr("Забрать разрешение"), onRevoke)

        Text(tr("Выложить по открытой ссылке"), style = PointType.body)
        Text(
            tr("Про такое Point спрашивает каждый раз и ответ не запоминает: ссылка на сутки — " +
                "каждый раз новая ставка."),
            style = PointType.small,
        )

        // Та же строка, что на телефоне (#1487): политика открывается из самого Point.
        PortalRow(
            title = com.point.core.flow.PRIVACY_POLICY_TITLE,
            subtitle = com.point.core.flow.PRIVACY_POLICY_WHAT,
            onClick = onOpenPolicy,
            subtitleMaxLines = 3,
        )
    }
}

/** Сказанное после уборки: молчание читалось как «не сработало» (#1081). */
internal fun sweptText(swept: Int?): String = when {
    swept == null -> tr("Убрать прямо сейчас")
    swept == 0 -> tr("Убирать было нечего")
    else -> tr("Убрано объектов: {0}", swept)
}

/** Экран данных: что лежит и как это убрать. */
@Composable
fun SettingsData(swept: Int?, onSweepNow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            tr("Point не хранит дольше суток: присланное с телефона и сделанное здесь убирается " +
                "само. Кнопка убирает это прямо сейчас — вместе со списком «Недавнего». " +
                "Файл, который вы перетащили мышью, не трогается никогда."),
            style = PointType.small,
        )
        Action(sweptText(swept), onSweepNow)
    }
}

@Composable
internal fun Verdict(verdict: KeyVerdict) {
    when (verdict) {
        is KeyVerdict.Works -> Text(com.point.core.flow.keyWorksLine(verdict), style = PointType.small)
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title.uppercase(), style = PointType.label)
            Box(Modifier.weight(1f).height(1.dp).background(PointColors.hairline))
        }
        Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp), content = content)
    }
}

/** Строка-переключатель: состояние сказано словом под названием и видно справа. */
@Composable
private fun SwitchRow(title: String, subtitle: String, on: Boolean, onToggle: () -> Unit) {
    PortalRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        trailing = { GlassToggle(on) },
    )
}

@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,

    /** Секрет можно показать буквами: точки не говорят, тот ли ключ встал и целиком ли. */
    revealable: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hint, style = PointType.small.copy(color = PointColors.muted))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PointType.body,
            cursorBrush = SolidColor(PointColors.violet),
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { inner() }
                    if (secret && revealable && value.isNotBlank()) {
                        Text(
                            if (shown) tr("Скрыть") else tr("Показать"),
                            style = PointType.small.copy(color = PointColors.link),
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(6.dp))
                                .handCursor().clickable { shown = !shown },
                        )
                    }
                }
            },
        )
    }
}

@Composable
internal fun Action(title: String, onClick: () -> Unit) {
    PillButton(title, onClick)
}
