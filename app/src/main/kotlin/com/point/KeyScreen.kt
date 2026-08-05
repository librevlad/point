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

/**
 * Экран настроек Point. Ключ человека, отправка наружу, аккаунт, звук, статистика.
 *
 * Приведён к языку портала (#114). Провайдер выглядит строкой действия, и выбранный — той самой
 * светящейся строкой, которой на экране объекта отмечено основное действие: «вот этот».
 *
 * **Путь до работающего ключа доведён до конца (#465).** Экран говорит, ЗАЧЕМ ключ, — до того, как
 * человек упёрся в отказ; шаги пронумерованы (взять → вставить → проверить); буфер обмена
 * принимается одним тапом; и главное — [onCheck] стучится в сервис по-настоящему и показывает
 * ответ. «Сохранить» молча записывал ключ на диск, и узнать, подошёл ли он, можно было только
 * следующим действием — то есть тогда, когда оно уже провалилось.
 *
 * **Композиция разобрана как целое (#447).** Список сервисов свёрнут в строку, «Взять ключ» уехало
 * из строки выбора и стало «Открыть сайт …», состояние ключа (`keySetLabel`) видно, не нажимая
 * ничего, а хвост экрана получил имена вместо склада.
 *
 * **Дверь работает в обе стороны (#536, #537).** Ключ можно забыть, а смена сервиса уносит чужой
 * ключ вместе с сервисом, которому он принадлежал.
 *
 * **Дверь на «Недавнем» осталась одна, и это она (#544).** Круг устройств — раздел [onOpenDevices]
 * здесь же; сам экран устройств (#472) переехал как есть.
 *
 * ---
 *
 * **Настройки читаются как настройки (#563).** Всё перечисленное выше стояло на одном полотне
 * подряд: заголовок, абзац, кнопка, ещё абзац, поле, ещё абзац — и так пять разделов. Каждый абзац
 * написан по делу, но вместе они стена: чтобы найти «Звук действий», человек прокручивал три экрана
 * текста про ключ AI. Владелец: «неудобно читать — сделай как у всех, там ещё много будет».
 *
 * Экран стал **двухуровневым**, и это единственное, что изменилось: ни одного слова не переписано,
 * ни одной настройки не добавлено и не убрано — тексты переехали туда, где их ищут.
 *
 * 1. **Общий экран — список из пяти строк:** ключ AI · отправка и приватность · мои устройства ·
 *    звук · приватная статистика. Строка = название + одна строка подписи + состояние. Состояние
 *    видно, не открывая: «Ключ на устройстве: sk-o…3456» / «Ключа пока нет», выбранный уровень
 *    приватности, положение тумблера.
 * 2. **Подробности живут внутри своего раздела.** Три шага получения ключа остались мастером — но
 *    мастером, в который человек попадает, когда идёт за ключом, а не каждый раз при открытии
 *    настроек. Туда же уехали объяснения про облако, уровни приватности, звук и статистику.
 * 3. **Формат держит рост.** Добавить настройку — значит добавить строку в [SettingsList], а не
 *    абзац в общее полотно. Именно поэтому строки общего экрана **без иконных плит**: плита — язык
 *    действия над объектом, а здесь список разделов, и одинаковые строки читаются быстрее пёстрых.
 * 4. **Тумблеры остаются на общем экране** (звук, статистика): включить звук — одно движение, и
 *    отправлять человека за ним внутрь значило бы чинить чтение ценой действия. Тап по самой строке
 *    открывает раздел «Приложение», где стоит полное объяснение, которое в одну строку не влезло.
 *
 * Раздел ключа открывается сразу, если человека сюда привёл отказ ([note]), поручение от действия
 * ([errand]) или он только что проверял ключ ([checking]/[verdict]): экран, открытый по поводу
 * ключа, обязан показать ключ.
 *
 * ---
 *
 * **Путь больше не теряет объект (#465, вторая половина).** Всё перечисленное чинило сам экран, и
 * оставалось последнее: экран не знал, ОТКУДА человек пришёл. Он тапал «Понять · нужен ключ» на
 * своей фотографии, ждал минуту, получал отказ, шёл по предложению сюда — и оказывался в
 * настройках, где о его объекте не сказано ни слова. Уйдя отсюда, он попадал на «Недавнее», а не к
 * своей фотографии, и заново вспоминал, ради чего всё затевалось.
 *
 * Теперь тап по такому действию ведёт сюда сразу (минуту ожидания ради заведомого отказа Point не
 * тратит), а с человеком приезжает [errand]: имя действия и имя объекта. Ими раздел ключа говорит,
 * зачем ключ ИМЕННО здесь, и ими же называет дверь обратно — «Вернуться к «чек.jpg»», сразу под
 * «Работает». Обе стоят внутри [KeySection], а не на общем списке: список разделов — не место для
 * разговора про чью-то фотографию, и человек с поручением его вообще не видит.
 *
 * Что дверь НЕ делает — так это не выполняет прерванное действие: человек вернётся к объекту, где
 * оно стоит доступным и ждёт его тапа («Point никогда не строит автоматические цепочки», и «он же
 * сам его только что нажал» — не исключение).
 */
@Composable
fun KeyScreen(
    config: UserAiConfig,
    /** Отказ, который сюда привёл (#467): человек, выброшенный на семь провайдеров молча, получает
     *  ту самую «общую непонятную ошибку». null — пришёл сам, дверью «Настройки», и объяснять
     *  нечего. */
    note: String? = null,
    /** Поручение, с которым сюда пришли (#465): чьё имя назвать и куда вернуть. null — пришли
     *  сами, дверью «Настройки»: возвращать некуда, и экран об этом молчит. */
    errand: KeyErrand? = null,
    onSave: (UserAiConfig) -> Unit,
    onCancel: () -> Unit,
    usageEnabled: Boolean,
    usageSummary: UsageSummary?,
    onToggleUsage: (Boolean) -> Unit,
    /** Идёт ли живая проверка ключа прямо сейчас (#465). */
    checking: Boolean = false,
    /** Чем кончилась проверка — `null`, пока её не запускали (#465). */
    verdict: KeyVerdict? = null,
    /** Проверить ключ живым запросом; удачная проверка его же и сохраняет (#465). */
    onCheck: (UserAiConfig) -> Unit = {},
    /** Что лежит в буфере обмена — читается ТОЛЬКО по тапу «Вставить из буфера» (#465). */
    onPasteKey: () -> String? = { null },
    /** Стереть ключ с устройства (#536) — единственный путь отключить AI обратно. */
    onForgetKey: () -> Unit = {},
    soundEnabled: Boolean = true,
    onToggleSound: (Boolean) -> Unit = {},
    /** Кому вообще можно предлагать объект (#280) — умолчание «максимум бесплатного». */
    privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
    onPickPrivacyLevel: (PrivacyLevel) -> Unit = {},
    /** Разрешена ли отправка объектов моделям — и здесь же её можно забрать обратно (#114). */
    cloudEnabled: Boolean = false,
    onToggleCloud: (Boolean) -> Unit = {},
    /** Открыть страницу провайдера, где выдают ключ (#403). */
    onOpenUrl: (String) -> Unit = {},
    /** Аккаунт и круг устройств (#544) — раздел этого экрана, а не соседняя дверь на «Недавнем». */
    onOpenDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // `rememberSaveable`, а не `remember` (#114): поворот телефона пересоздаёт экран, и набранное
    // на `remember` пропадает молча. Здесь это особенно дорого — API-ключ длинный и обычно
    // вставлен из буфера: человек, повернувший телефон, шёл бы за ним второй раз.
    val draft = rememberSaveable(config, saver = KeyDraft.Saver) { KeyDraft(config) }
    // Какой раздел открыт; `null` — общий список (#563). Переживает поворот по той же причине, что
    // и набранное: человек, крутивший телефон в мастере ключа, не должен возвращаться в список.
    // Поручение (#465) поднимает раздел ключа на тех же правах, что отказ: человек тапнул
    // «Понять · нужен ключ» и пришёл сюда за ключом — высадить его в общий список настроек
    // значило бы заставить искать вход в то, ради чего его сюда и привели.
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
                // Верхний возврат стоит и на корне (#580): внутри разделов он есть, а здесь был
                // только нижний «Отмена» — один экран настроек вёл себя двумя разными способами.
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
                    // Дверь к объекту — тот же выход, что «Готово» внизу экрана: закрыть настройки
                    // целиком. Второй правды о выходе не заводится, а пересборку действий объекта
                    // делает `FlowViewModel.closeKeySettings()` — она достаётся всем выходам сразу.
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
        // Дорога в обход проверки остаётся: связи может не быть вовсе, а ключ вписывают заранее;
        // у кого-то свой прокси, который на пробный запрос не отвечает. Отнимать возможность
        // сохранить ради красоты пути нельзя — но и главной она больше не является. Стоит там же,
        // где всё про ключ, — внутри его раздела (#563).
        if (section == SettingsSection.KEY && draft.key.isNotBlank() && verdict !is KeyVerdict.Works) {
            TextButton(onClick = { onSave(draft.entered()) }) {
                Text("Сохранить без проверки", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onCancel) {
            // После удачной проверки уходить уже не «отменой»: ключ сохранён, дело сделано.
            Text(
                if (verdict is KeyVerdict.Works) "Готово" else "Отмена",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Разделы настроек (#563). Отсутствие раздела (`null`) — сам список.
 *
 * Устройств в перечислении нет намеренно: за их строкой стоит не свёрнутый текст, а другой экран со
 * своим состоянием (круг едет с сервера, поднимается вход, отключается устройство). Открывает его
 * не этот экран собой, а вызов наверх — так же, как было до #563.
 */
private enum class SettingsSection { KEY, PRIVACY, APP }

/**
 * Общий экран настроек: три группы, пять строк, и у каждой видно состояние (#563).
 *
 * Ни одного абзаца: всё, что длиннее строки, живёт внутри своего раздела. Строка = название +
 * подпись в одну строку + состояние (текстом или тумблером).
 *
 * **Строки собраны в группы с заголовками** — так настройки устроены везде, где человек их читал до
 * Point (системные, Telegram, Claude), и взгляд ищет именно эту структуру. Плоский список из пяти
 * строк владелец забраковал ровно за это: «настройки надо по секциям! как в телеграме, как в
 * Claude, как везде». Группа названа тем, что в ней лежит, а не повторяет строку: «Ключ AI» и
 * «Отправка и приватность» — это всё про AI и облако; «Мои устройства» — про аккаунт; звук со
 * статистикой — про само приложение.
 *
 * Добавить настройку — добавить строку в нужную группу; появится настройка не из этих трёх — новую
 * группу из двух строчек кода. Абзацу места по-прежнему нет.
 */
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
        // Экран называется тем, что он есть (#447), и ровно тем же словом, что дверь, которой сюда
        // пришли (#544). Подписи под заголовком больше нет (#580): она перечисляла то, что видно
        // тремя сантиметрами ниже секциями, и заканчивалась похвальбой — «больше Point ни о чём не
        // спрашивает». Карту экрана рисует сама раскладка.
        ScreenHeader(title = SETTINGS_TITLE, modifier = Modifier.padding(bottom = 2.dp))

        SettingsGroup(AI_GROUP_TITLE) {
            // Состояние ключа — той же строкой, что стоит внутри раздела (`keySetLabel`): задан ли
            // ключ, человек узнаёт, не открывая ничего. Про «работает» она молчит — это знает
            // только сервис.
            SettingsRow(
                title = KEY_SECTION_TITLE,
                subtitle = keyLine,
                onClick = { onOpen(SettingsSection.KEY) },
                appearIndex = 0,
            )
            GroupSeam()
            SettingsRow(
                title = PRIVACY_SECTION_TITLE,
                // Оба состояния раздела разом: отпущен ли объект наружу вообще и кому его можно
                // предлагать.
                subtitle = (if (cloudEnabled) "Облако разрешено" else "Облако выключено") +
                    " · ${privacyLevel.title}",
                onClick = { onOpen(SettingsSection.PRIVACY) },
                appearIndex = 1,
            )
        }

        SettingsGroup(ACCOUNT_GROUP_TITLE) {
            // Строка ведёт в тот же экран (#472), что и прежняя вторая дверь «Недавнего» (#544).
            SettingsRow(
                title = MY_DEVICES_TITLE,
                subtitle = "Вход, круг устройств и выход.",
                onClick = onOpenDevices,
                appearIndex = 2,
            )
        }

        SettingsGroup(APP_SECTION_TITLE) {
            // Тумблер стоит здесь, а не внутри: включить звук — одно движение. Тап по строке
            // открывает «Приложение», где то же самое объяснено полностью.
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

/**
 * Группа настроек: карточка с шапкой-именем и строками внутри неё, вплотную.
 *
 * Эталон — настройки Telegram, которые владелец прислал словами «как в телеграме, как везде»:
 * группы стоят карточками с крупным скруглением, между карточками воздух, имя группы написано
 * вверху самой карточки акцентным цветом. Ни поверхность, ни скругление, ни цвет для этого не
 * выдуманы: карточку рисует тот же [portalCard], каким её рисовала каждая строка по отдельности
 * (теперь она одна на группу, а строки стоят без своей — `surface = false`), скругление — общее
 * `PortalCardShape`, а акцент шапки — `primary` темы, то есть АКЦЕНТ1 портала.
 *
 * Шов между строками ([GroupSeam]) — волосяная черта ГРАНИЦЫ. У Telegram его нет, потому что там
 * строку опознаёт цветная плита; у нас плит на этом экране нет намеренно (см. [SettingsRow]), и без
 * шва две двустрочные строки сливаются в один абзац — ровно то, от чего лечит весь этот срез.
 */
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

/** Шов между строками одной группы: волосяная черта ГРАНИЦЫ, с отступом слева — как везде. */
@Composable
private fun GroupSeam() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Строка общего экрана: название, одна строка подписи, состояние справа.
 *
 * Без иконной плиты — в отличие от строки действия на экране объекта. Плита там значит «вот чем это
 * будет сделано»; в списке разделов ей значить нечего, а платит за неё подпись: плита забирает
 * шестьдесят точек ширины, и состояние («Ключа пока нет — без него AI-действия молчат») в одну
 * строку перестаёт помещаться.
 *
 * Предела в одну строку здесь БОЛЬШЕ НЕТ (#580). Подпись настройки говорит её состояние, а
 * обрезанное состояние равно несказанному: человек читал «Ключа пока нет — без него AI-действия
 * молч…» и не узнавал последствия. В списках действий предел остаётся — там подписи однотипные, и
 * ровные строки помогают сравнивать; в настройках сравнивать нечего.
 *
 * Собственной поверхности у строки нет: её рисует [SettingsGroup] — одну на всю группу.
 */
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

/** Воздух между карточками групп. Заметно больше шага внутри группы — иначе они сливаются. */
private val GroupGap = 16.dp

/** Выход из настроек — той же стрелкой, что возврат из раздела: один экран, один приём (#580). */
@Composable
private fun BackToRoot(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("← Назад", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Возврат к списку разделов — тем же словом, каким назван экран, и той же стрелкой, что выход. */
@Composable
private fun BackToList(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Text("← $SETTINGS_TITLE", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Мастер ключа: взять → вставить → проверить (#465), теперь внутри своего раздела (#563).
 *
 * Три шага никуда не делись и по-прежнему живут на одном экране — просто на этот экран человек
 * попадает, когда идёт за ключом, а не всякий раз, когда открыл настройки.
 *
 * Сюда же въезжает поручение (#465): [errand] — то, ради чего человек за ключом и пошёл. Раздел
 * называет его действие по имени в начале пути и называет его объект в конце — строкой [onLeave].
 */
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

    // Зачем человека сюда принесло (#467). Стоит НАД шагами: это ответ на вопрос, с которым он
    // пришёл. Карточка та же, что под объектом, и голос тот же: это одна и та же новость, просто
    // досказанная там, где её можно устранить.
    if (note != null) OutcomeBanner(message = note, outcome = Outcome.FAILED)

    // Зачем ключ ИМЕННО тому действию, по которому человек тапнул (#465).
    //
    // Стоит над Шагом 1 и **не красным**: здесь ничего не сломалось. Человек нажал
    // «Понять · нужен ключ» — Point не стал тратить его минуту на заведомый отказ, а назвал цену и
    // повёл коротким путём. Знака исхода у карточки поэтому нет: `FAILED` сообщал бы о неудаче,
    // `DONE` — о сделанном, а произошло ни то ни другое.
    if (errand != null) {
        OutcomeCard(
            title = com.point.core.flow.keyErrandWhy(errand.action),
            outcome = Outcome.NONE,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Выбор провайдера вместо трёх полей наизусть: адрес и модель подставляются сами, а рядом
    // лежит ссылка на страницу, где ключ выдают. Выбранный провайдер не хранится отдельным
    // состоянием, а читается из адреса: два состояния об одном расходятся при первом же
    // восстановлении экрана (#114).
    val chosen = providerForBaseUrl(draft.baseUrl)
    SectionLabel("Шаг 1 · Откуда взять ключ")
    // Зачем ключ — здесь, а не в заголовке экрана (#447): это довод первого шага, а не всех
    // настроек. Слова общие с приглашением на «Недавнем» (`AI_KEY_WHY`).
    Text(
        "$AI_KEY_WHY Point работает на вашем ключе и вашей квоте — чужие ключи он не " +
            "хранит и не просит.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // «Взять ключ» больше не стоит внутри строки выбора и не называется «взять» (#447).
    // Ссылка относится к ВЫБРАННОМУ сервису: сходить за ключом больше не к кому.
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
    // Семь сервисов занимали весь первый экран ради выбора, который делают один раз.
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
                        // Ключ принадлежит сервису, а не экрану (#537). Прежде при смене сервиса в
                        // поле оставался ключ от предыдущего, «Проверить» честно отвечало «Ключ не
                        // подошёл» — и человек читал это как «продукт сломан», хотя не сделал
                        // ничего неправильного. Чужой ключ уходит вместе с сервисом, которому
                        // принадлежал; повторный тап по УЖЕ выбранному ничего не стирает.
                        if (chosen?.id != provider.id) {
                            draft.key = ""
                            draft.pasteNote = ""
                        }
                        draft.baseUrl = provider.baseUrl
                        draft.model = provider.models.substringBefore(',')
                        draft.servicesOpen = false // выбрал — список свернулся, экран не растёт
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
    // Ключ приезжает из буфера — человек только что скопировал его на чужой странице. Буфер
    // читается ТОЛЬКО по этому тапу: заглядывать в него, чтобы решить, показывать ли строку,
    // значило бы читать чужое без спроса ради украшения экрана.
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
                    // Честно про пустой результат: молчание тут неотличимо от «не нажалось».
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
    // Задан ключ или нет — видно, не нажимая ничего (#447). Той же строкой это сказано на общем
    // экране (#563): человеку, зашедшему посмотреть, теперь не надо открывать и этот раздел.
    OutcomeCard(
        title = keySetLabel(draft.key, saved = draft.savedIn(config)),
        outcome = Outcome.NONE,
        modifier = Modifier.fillMaxWidth(),
    )
    // Путь обратно (#536). `UserKeyStore.clear()` был объявлен и не звался ниоткуда: отключить AI
    // человек не мог ничем, кроме переустановки, — а поводы настоящие (отдать телефон, уйти с
    // рабочего ключа, перестать платить своей квотой). Без диалога подтверждения — по образцу
    // соседнего необратимого «Отключить» на экране устройств: цена ошибки мала и обратима.
    if (config.apiKey.isNotBlank()) {
        PortalRow(
            title = "Забыть ключ",
            subtitle = "Point сотрёт его с устройства. «Понять», «Перевести», «Спросить AI» " +
                "и расшифровка записи снова замолчат, пока не впишете новый.",
            onClick = {
                // Поле пустеет сразу: стёртое человек должен УВИДЕТЬ, а не догадаться.
                draft.key = ""
                draft.pasteNote = ""
                onForgetKey()
            },
            chevron = false,
            subtitleMaxLines = 3,
        )
    }

    // Адрес и модель остаются правимыми — у кого-то свой прокси, и отнимать это ради красоты
    // нельзя. Но спрашивались они у всех, а нужны почти никому: свёрнуты, и подпись показывает их
    // значения, так что видно, куда и чем Point ходит, не раскрывая блок.
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
        // Что именно уедет при проверке — прежде, чем человек нажмёт. Объект тут ни при чём.
        "Point спросит сервис одним коротким словом и покажет ответ. Ваш объект при этом " +
            "никуда не отправляется.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // «Основное действие» раздела — та же светящаяся строка, что главное действие объекта.
    // Пока ключа нет, она притушена: строка светится, когда может.
    val canCheck = draft.key.isNotBlank() && !checking
    PortalRow(
        // Слово меняется вместе с состоянием: «Проверяю…» над идущим запросом — это тот же честный
        // статус, что и на экране ожидания, а не застывшая кнопка.
        title = if (checking) "Проверяю…" else "Проверить и включить",
        onClick = { onCheck(draft.entered()) },
        icon = bubbleIcon(AI_ICON),
        // Пока проверка не сказала «работает», главное здесь — она. Сказала — главным становится
        // возврат к объекту, и светится он: двух светящихся строк подряд в Point не бывает, иначе
        // они спорят за один тап.
        primary = verdict !is KeyVerdict.Works,
        chevron = false,
        enabled = canCheck,
        modifier = Modifier.graphicsLayer { alpha = if (canCheck) 1f else 0.45f },
    )
    // Приговор стоит прямо под кнопкой, которая его вызвала: «работает» человек должен УВИДЕТЬ, а
    // не додумать, а отказ обязан сказать, что именно не так и что с этим делать.
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

    // Дверь обратно к объекту — названная (#465).
    //
    // Технически она была всегда: «Готово» внизу экрана закрывает настройки, и под ними стоит тот
    // самый объект. Но человек этого не знал: слово «Готово» говорит о ключе, а не о фотографии, и
    // стоит оно за краем длинной прокрутки, ниже ещё и «Сохранить без проверки». Строка называет
    // то место, куда ведёт, и стоит там, где кончился путь, — сразу под «Работает».
    //
    // Подпись говорит вторую половину правды, без которой возврат стал бы ловушкой: прерванное
    // действие Point за человека НЕ выполнит. Тапнуть по нему — его выбор, ровно как в первый раз
    // («Point никогда не строит автоматические цепочки»).
    if (errand != null && verdict is KeyVerdict.Works) {
        PortalRow(
            title = "Вернуться к «${errand.objectName}»",
            subtitle = "«${errand.action}» ждёт там — уже без приписки про ключ. Тапнуть по нему " +
                "Point за вас не станет.",
            onClick = onLeave,
            // Без иконной плиты: у «вашего объекта» нет одного знака — сегодня это фото, завтра
            // PDF, послезавтра запись. Любой выбранный врал бы в двух случаях из трёх, а стрелка
            // «назад» в этом разделе уже занята выходом в список настроек.
            primary = true,
            chevron = false,
            subtitleMaxLines = 3,
        )
    }
}

/**
 * Всё, что уходит наружу, — под одним именем (#447), и теперь своим разделом (#563).
 *
 * Три настройки отвечают на один вопрос — что уезжает с устройства, — и потому стоят вместе:
 * разрешение отпускать объект, кому его можно предлагать и по какой цене.
 */
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

    // Отозвать разрешение было негде: человек, разрешивший облако одним тапом когда-то, не мог
    // передумать ничем, кроме переустановки (#114). Тумблер стоит первым — это самое дорогое из
    // здешних решений: им объект уезжает с устройства. Соседняя настройка «Куда можно отправлять»
    // (#280) отвечает на другой вопрос — кому МОЖНО предлагать.
    SwitchCard(
        title = "Отправка в облако",
        description = "Разрешает показывать объект моделям — по вашему тапу и с названием того, " +
            "куда он уедет. Выключите, и Point спросит заново. Выложить файл по открытой " +
            "ссылке этим тумблером нельзя: про такое спрашивают каждый раз.",
        checked = cloudEnabled,
        onCheckedChange = onToggleCloud,
    )

    // «Куда можно отправлять» (#280) — тем же строем, что выбор сервиса: список строк, где
    // выбранная светится. Полоска узких чипов показывала цену только выбранного варианта; список
    // строк держит цену при **каждом**.
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
            // Цена — при варианте, а не в справке: выбор без цены это не выбор.
            subtitle = level.what,
            onClick = { onPickPrivacyLevel(level) },
            // Без иконной плиты намеренно: у трёх уровней нет трёх разных знаков, а один общий
            // (облако) врал бы на «Только на телефоне» — там как раз ничего не уходит.
            primary = level == privacyLevel,
            chevron = false,
            subtitleMaxLines = 4,
            appearIndex = index,
        )
    }
}

/**
 * Само приложение: две вещи, которые включают и забывают (#447).
 *
 * Раздел открывается из ЛЮБОЙ из двух строк общего экрана, и обе строки продолжают переключаться
 * оттуда одним тапом (#563). Сюда человек приходит не переключать, а прочитать — что именно звучит
 * и что именно считается; в одну строку это не влезло, и потому лежит здесь.
 */
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
        // Итог живёт внутри своей карточки: он и есть то, что этот тумблер насчитал.
        tally = usageSummary
            ?.takeIf { usageEnabled }
            ?.let { "Объектов: ${it.objects} · действий: ${it.actions} · завершено в Point: ${it.completed}" },
    )
}

/**
 * Как называются группы общего экрана (#563).
 *
 * Группа называет то, что в ней лежит, а не повторяет строку: над «Ключом AI» и «Отправкой и
 * приватностью» стоит «AI и облако», а не «Ключ AI». «Приложение» — то же слово, что у раздела за
 * этими двумя строками: группа на списке и раздел за ним — одно и то же место.
 */
private const val AI_GROUP_TITLE = "AI и облако"
private const val ACCOUNT_GROUP_TITLE = "Аккаунт"

/** Как называются разделы — одним словом на список и на сам раздел, чтобы они не разъехались. */
private const val KEY_SECTION_TITLE = "Ключ AI"
private const val PRIVACY_SECTION_TITLE = "Отправка и приватность"
private const val APP_SECTION_TITLE = "Приложение"
private const val SOUND_TITLE = "Звук действий"
private const val USAGE_TITLE = "Приватная статистика"

/**
 * Черновик ключа: то, что человек набрал, но ещё не отдал на хранение.
 *
 * Живёт выше разделов (#563) — иначе набранное пропадало бы при возврате в список, — и переживает
 * поворот телефона (#114): API-ключ длинный и обычно вставлен из буфера, и человек, повернувший
 * телефон, шёл бы за ним второй раз. Раскрытость свёрнутых блоков здесь же по той же причине:
 * список сервисов не должен схлопываться под пальцем.
 */
private class KeyDraft(config: UserAiConfig) {
    var key by mutableStateOf(config.apiKey)
    var model by mutableStateOf(config.model)
    var baseUrl by mutableStateOf(config.baseUrl)

    /** Что ответила вставка, когда в буфере оказался не ключ. Пусто — про буфер сказать нечего. */
    var pasteNote by mutableStateOf("")

    /** Сервис выбирают один раз, адрес и модель — почти никогда: оба блока свёрнуты (#447). */
    var servicesOpen by mutableStateOf(false)
    var advancedOpen by mutableStateOf(false)

    /** Ровно то, что уйдёт на проверку или в хранилище. */
    fun entered() = UserAiConfig(key.trim(), baseUrl.trim(), model.trim())

    /** Лежит ли набранное на устройстве — или человек его только что вписал и ещё не сохранил. */
    fun savedIn(config: UserAiConfig) = key.trim() == config.apiKey.trim() && key.isNotBlank()

    companion object {
        val Saver = listSaver<KeyDraft, Any>(
            // Порядок тот же, что у `UserAiConfig`: ключ, адрес, модель — и три служебных признака.
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

/**
 * Сервис в списке: имя, чем он хорош, что известно про бесплатность.
 *
 * Кнопки «Взять ключ» здесь больше нет (#447). Довод, которым она сюда ставилась, верен —
 * «сходить за ключом» и «выбрать этого» разные желания, — но решение было неверным: две кнопки в
 * одной строке различаются только подписью, а место у них общее, и место читается первым. Владелец
 * прочитал «Взять ключ» как «взять этот ключ». Теперь у строки одно значение — «вот этот», — а
 * ссылка на страницу стоит отдельной строкой над списком и относится к выбранному.
 */
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

/**
 * Строка, за которой сложено то, что нужно не всем: список сервисов, модель и адрес.
 *
 * Не переход в раздел, а раскрытие на месте: это правка того, что лежит прямо здесь, и уводить
 * человека ради неё на третий уровень было бы дорого. Подпись показывает текущее значение, поэтому
 * свёрнутый блок ничего не прячет — он прячет только правку.
 */
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

/** Раскрытие блока — тем же движением, каким появляется карточка исхода. */
@Composable
private fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { content() }
}

/** Иконка AI из общего словаря — тем же знаком подписано действие «AI» на экране объекта. */
private const val AI_ICON = "ai"

/** Тумблер карточкой портала: что включаем, что это значит и — если есть — что уже насчитано. */
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
    // То, ради чего срез: пять строк, и у каждой видно состояние. Абзацев нет ни одного — они
    // лежат внутри разделов, где их ищут.
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
    // Человек открыл настройки посмотреть, а не настраивать: задан ключ или нет, видно первой же
    // строкой — раньше за этим надо было идти в мастер.
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
    // Приговор открывает раздел ключа сам: экран, открытый по поводу ключа, показывает ключ.
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
    // Отказ с продолжением: что именно не так и что с этим делать. «Ошибка» без совета оставляет
    // человека ровно там, откуда он пришёл.
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
    // Человек тапнул «Понять · нужен ключ» на своей фотографии. Он сразу в разделе ключа, и первое,
    // что читает, — про «Понять» и про свой объект, а не список настроек.
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
    // То, ради чего весь срез: ключ работает, и светится теперь дверь ОБРАТНО — с именем объекта и
    // честной оговоркой, что тапать по «Понять» Point за человека не станет.
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
