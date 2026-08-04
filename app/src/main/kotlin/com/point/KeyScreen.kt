package com.point

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiProvider
import com.point.core.flow.KeyCheck
import com.point.core.flow.KeyStatusLine
import com.point.core.flow.KeyTone
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.flow.checkFor
import com.point.core.flow.keyFingerprint
import com.point.core.flow.keyStatusLine
import com.point.core.flow.providerForBaseUrl
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalPlate
import com.point.core.ui.PortalRow
import com.point.core.ui.PortalWarm
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme

/**
 * Настройки Point — служебное место, вызываемое шестерёнкой, а не второй дом (#447).
 *
 * **Что здесь было не так.** Владелец, увидев экран живьём: «в настройках неинтуитивные кнопки, что
 * делает кнопка Взять ключ — непонятно, задан он или нет — непонятно. до полей ввода надо
 * скроллить». Три жалобы — три разные ошибки, и все три от того, что экран не проектировался
 * целиком: он рос приращениями, и каждое приращение вставало сверху.
 *
 * 1. **Наверху стоял список из семи провайдеров** — то, что человек выбирает один раз. Поле ключа,
 *    ради которого экран и открывают, уезжало за нижний край. Теперь порядок — по тому, зачем сюда
 *    приходят: состояние ключа, поле ключа, проверка, сохранение. Провайдер и адрес свёрнуты в одну
 *    строку каждый и раскрываются тапом, когда нужны.
 * 2. **«Взять ключ» стояло внутри строки провайдера** и читалось как «взять этот ключ» — то есть
 *    как выбор, а не как уход в браузер. Теперь это отдельная строка **под** полем, названная тем,
 *    что произойдёт: «Открыть сайт OpenRouter», и подписью — что там делать. Ушла и двусмысленность
 *    самой позиции: сходить за ключом можно только к тому провайдеру, который выбран.
 * 3. **Задан ключ или нет — не было видно.** Поле под точками: пустое и заполненное отличались
 *    числом точек. Теперь сверху карточка состояния ([KeyStatusCard]): хвост ключа, чей он, сохранён
 *    ли — и, если человек нажал «Проверить», что ответил провайдер на самом деле (`keyStatusLine`
 *    живёт в `:core:flow` и потому под тестом).
 *
 * **Проверка — живой запрос, а не проверка формы.** Правильная длина и правильный префикс не значат
 * ничего: ключ бывает отозван, исчерпан или от другого сервиса. Отказ показывается словами
 * провайдера и не сглаживается: 401 («опечатка») и 429 («лимит, а не ключ») требуют от человека
 * разного.
 *
 * Экран остаётся одним экраном: Point не становится приложением с меню (продуктовый фильтр). Внутри
 * — четыре группы и ни одного перехода: ключ, куда можно отправлять, приложение, выход.
 *
 * Имя `KeyScreen` осталось прежним намеренно: в соседней ветке правится логика ключей, и
 * переименование файла ради заголовка стоило бы разбора слияния на ровном месте.
 */
@Composable
fun KeyScreen(
    config: UserAiConfig,
    onSave: (UserAiConfig) -> Unit,
    onCancel: () -> Unit,
    usageEnabled: Boolean,
    usageSummary: UsageSummary?,
    onToggleUsage: (Boolean) -> Unit,
    soundEnabled: Boolean = true,
    onToggleSound: (Boolean) -> Unit = {},
    /** Кому вообще можно предлагать объект (#280) — умолчание «максимум бесплатного». */
    privacyLevel: com.point.core.flow.PrivacyLevel = com.point.core.flow.PrivacyLevel.DEFAULT,
    onPickPrivacyLevel: (com.point.core.flow.PrivacyLevel) -> Unit = {},
    /** Открыть страницу провайдера, где выдают ключ (#403). */
    onOpenUrl: (String) -> Unit = {},
    /** Что ответил провайдер на «Проверить ключ» (#447) — или что мы ещё не спрашивали. */
    keyCheck: KeyCheck = KeyCheck.Untested,
    onCheckKey: (UserAiConfig) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // `rememberSaveable`, а не `remember` (#114): поворот телефона пересоздаёт экран, и набранное
    // на `remember` пропадает молча. Здесь это особенно дорого — API-ключ длинный и обычно
    // вставлен из буфера: человек, повернувший телефон, шёл бы за ним второй раз.
    var key by rememberSaveable(config) { mutableStateOf(config.apiKey) }
    var model by rememberSaveable(config) { mutableStateOf(config.model) }
    var baseUrl by rememberSaveable(config) { mutableStateOf(config.baseUrl) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var providersOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    // Выбранный провайдер не хранится отдельным состоянием, а читается из адреса: два состояния об
    // одном и том же расходятся при первом же восстановлении экрана (#114).
    val chosen = providerForBaseUrl(baseUrl)
    val edited = UserAiConfig(key.trim(), baseUrl.trim(), model.trim())
    // Ответ, полученный на других настройках, — уже не ответ: правка поля гасит отметку.
    val check = checkFor(keyCheck, keyFingerprint(edited))
    val saved = edited == UserAiConfig(config.apiKey.trim(), config.baseUrl.trim(), config.model.trim())
    val status = keyStatusLine(key, chosen?.name, saved, check)

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
            ScreenHeader(
                title = "Настройки",
                subtitle = "Ключ AI, куда можно отправлять объекты и звук. Point спрашивает об этом здесь и больше нигде.",
                modifier = Modifier.padding(bottom = 9.dp),
            )

            // --- Ключ AI: сначала «что сейчас», потом само поле, потом что с ним делать ---

            SectionLabel("Ключ AI")
            KeyStatusCard(status, running = check is KeyCheck.Running)

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API-ключ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                // Глаз рядом с полем — вторая половина ответа «задан или нет»: карточка показывает
                // хвост, глаз даёт убедиться, что вставилось целиком, а не половина из буфера.
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "Скрыть ключ" else "Показать ключ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Проверка стоит ПЕРЕД сохранением, потому что так это и делают: вставил — спросил —
            // сохранил. Пока проверять нечего, строка притушена: строка светится, когда может.
            val canCheck = key.isNotBlank() && check !is KeyCheck.Running
            PortalRow(
                title = if (check is KeyCheck.Running) "Спрашиваем провайдера…" else "Проверить ключ",
                subtitle = "Один короткий запрос к провайдеру — тот же, каким пойдёт настоящее действие.",
                onClick = { onCheckKey(edited) },
                icon = bubbleIcon(AI_ICON),
                accent = bubbleColor(AI_ICON),
                chevron = false,
                enabled = canCheck,
                modifier = Modifier.graphicsLayer { alpha = if (canCheck) 1f else 0.45f },
            )

            // «Основное действие» экрана — та же светящаяся строка, что главное действие объекта.
            val canSave = key.isNotBlank()
            PortalRow(
                title = "Сохранить",
                onClick = { onSave(edited) },
                icon = bubbleIcon("save"),
                primary = true,
                chevron = false,
                enabled = canSave,
                modifier = Modifier.graphicsLayer { alpha = if (canSave) 1f else 0.45f },
            )

            // «Взять ключ» больше не стоит внутри строки провайдера и не называется «взять».
            // Название говорит, что произойдёт (откроется сайт), подпись — зачем туда идти.
            PortalRow(
                title = if (chosen != null) "Открыть сайт ${chosen.name}" else "Сначала выберите провайдера",
                subtitle = if (chosen != null) {
                    "Там выдают ключ: зарегистрируйтесь, скопируйте ключ и вставьте в поле выше. Откроется браузер."
                } else {
                    "Ключ выдаёт провайдер — выберите его строкой ниже, и сюда встанет ссылка на его сайт."
                },
                onClick = { if (chosen != null) onOpenUrl(chosen.keyUrl) else providersOpen = true },
                icon = bubbleIcon("open"),
                accent = bubbleColor("open"),
                chevron = false,
                subtitleMaxLines = 3,
            )

            // Провайдера выбирают один раз — значит, он свёрнут в одну строку, а не занимает экран.
            DisclosureRow(
                title = "Провайдер",
                subtitle = chosen?.let { "${it.name} · ${it.what}" } ?: "не выбран",
                open = providersOpen,
                onToggle = { providersOpen = !providersOpen },
            )
            Reveal(providersOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    AI_PROVIDERS.forEachIndexed { index, provider ->
                        ProviderRow(
                            provider = provider,
                            selected = chosen?.id == provider.id,
                            index = index,
                            onChoose = {
                                baseUrl = provider.baseUrl
                                model = provider.models.substringBefore(',')
                                providersOpen = false // выбрал — список свернулся, экран не растёт
                            },
                        )
                    }
                }
            }

            // Адрес и модель остаются правимыми (у кого-то свой прокси), но не стоят на виду: это
            // знание разработчика, а спрашивали его здесь у всех. Подпись показывает их значения —
            // не раскрывая блок, видно, куда и чем Point ходит.
            DisclosureRow(
                title = "Модель и адрес",
                subtitle = listOf(model, baseUrl).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "подставятся вместе с провайдером" },
                open = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
            )
            Reveal(advancedOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Модель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Адрес сервиса") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // --- Куда можно отправлять (#280) ---
            //
            // Тем же строем, что выбор провайдера: список строк, где выбранная светится. Первым
            // решением была полоска узких чипов с ценой выбранного отдельной строкой ниже; у списка
            // есть свойство, которого у чипов нет: цена стоит при **каждом** варианте.
            Spacer(Modifier.height(14.dp))
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
                    // Без иконной плиты намеренно: у трёх уровней нет трёх разных знаков, а один
                    // общий (облако) врал бы на «Только на телефоне» — там как раз ничего не уходит.
                    primary = level == privacyLevel,
                    chevron = false,
                    subtitleMaxLines = 4,
                    appearIndex = index,
                )
            }

            // --- Само приложение: две вещи, которые включают и забывают ---
            Spacer(Modifier.height(14.dp))
            SectionLabel("Приложение")
            SwitchCard(
                title = "Звук действий",
                description = "Тихий фирменный отклик на каждое действие. Вибрация управляется системной настройкой касаний.",
                checked = soundEnabled,
                onCheckedChange = onToggleSound,
            )
            SwitchCard(
                title = "Приватная статистика",
                description = "Обезличенно, только на устройстве — мерит, экономит ли Point переключения между приложениями.",
                checked = usageEnabled,
                onCheckedChange = onToggleUsage,
                // Итог живёт внутри своей карточки: он и есть то, что этот тумблер насчитал.
                tally = usageSummary
                    ?.takeIf { usageEnabled }
                    ?.let { "Объектов: ${it.objects} · действий: ${it.actions} · завершено в Point: ${it.completed}" },
            )
        }

        Spacer(Modifier.height(18.dp))
        // Выход называет цену выхода: несохранённая правка ключа теряется молча, и раньше об этом
        // не говорило ничего — кнопка называлась «Отмена» независимо от того, есть что терять.
        TextButton(onClick = onCancel) {
            Text(
                if (saved) "Закрыть" else "Закрыть без сохранения",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Что сейчас с ключом — первое, что человек видит на экране.
 *
 * Карточка портала, как карточка исхода на экране объекта: свет говорит второе сообщение после
 * текста. «Работает» — свет самого портала, «не принят» — тёплый конец фирменного градиента,
 * «не проверен» — без света вовсе: неизвестность не имеет права выглядеть ни удачей, ни отказом.
 */
@Composable
private fun KeyStatusCard(status: KeyStatusLine, running: Boolean) {
    val accent = when (status.tone) {
        KeyTone.GOOD -> MaterialTheme.colorScheme.primary
        KeyTone.BAD -> PortalWarm
        KeyTone.NEUTRAL -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .portalCard(accent = accent)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (running) {
            // Ждут тем же пульсом, каким Point думает над объектом (MOTION.md принцип №3).
            Row(
                modifier = Modifier.size(46.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { ThinkingDot() }
        } else {
            PortalPlate(accent = accent ?: bubbleColor("save"), icon = bubbleIcon(AI_ICON))
        }
        Column(Modifier.weight(1f)) {
            Text(
                status.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Строка, за которой сложено то, что нужно не всем: провайдер, модель и адрес.
 *
 * Не переход на другой экран, а раскрытие на месте: настройки Point — одно место, и уходить из него
 * вглубь некуда (продуктовый фильтр). Подпись показывает текущее значение, поэтому свёрнутый блок
 * ничего не прячет — он прячет только правку.
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

/**
 * Провайдер в списке: имя, чем он хорош и что известно про бесплатность.
 *
 * Кнопки «Взять ключ» здесь больше нет (#447): «сходить за ключом» и «выбрать этого» — разные
 * желания, и, стоя внутри строки выбора, вторая кнопка читалась как первая. Теперь у строки одно
 * значение — «вот этот», — а ссылка на сайт живёт отдельной строкой и относится к выбранному.
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
        Spacer(Modifier.width(2.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(name = "Настройки · ключа ещё нет (#447)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsNoKey() = PointTheme(darkTheme = true) {
    // То, что человек видит в первый раз. Карточка состояния прямо говорит, что ключа нет, поле
    // ключа стоит вторым — до него не нужно скроллить, — а «Сохранить» не светится: нечего.
    KeyScreen(
        config = UserAiConfig.DEFAULT,
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
    )
}

@Preview(name = "Настройки · ключ сохранён, не проверен (#447)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsKeySaved() = PointTheme(darkTheme = true) {
    // Ключ есть: карточка называет его хвост и чей он. «Ещё не проверен» — честно: мы правда не
    // спрашивали, и выдавать это за рабочий ключ было бы обещанием, которого никто не давал.
    KeyScreen(
        config = UserAiConfig("sk-or-v1-9c2f4d7ab31e", AI_PROVIDERS.first().baseUrl, "google/gemma-4-31b-it:free"),
        onSave = {},
        onCancel = {},
        usageEnabled = true,
        usageSummary = UsageSummary(objects = 42, actions = 118, completed = 31),
        onToggleUsage = {},
    )
}

@Preview(name = "Настройки · спрашиваем провайдера (#447)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsChecking() = PointTheme(darkTheme = true) {
    // Пока провайдер думает, карточка пульсирует тем же знаком, которым Point думает над объектом,
    // а строка проверки называет саму себя — второй тап смысла не имеет и погашен.
    KeyScreen(
        config = UserAiConfig("sk-or-v1-9c2f4d7ab31e", AI_PROVIDERS.first().baseUrl, "google/gemma-4-31b-it:free"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        keyCheck = KeyCheck.Running,
    )
}

@Preview(name = "Настройки · ключ работает (#447)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsKeyWorks() = PointTheme(darkTheme = true) {
    // Ответ провайдера, а не наша догадка: кто ответил, какой моделью и за сколько.
    val config = UserAiConfig("sk-or-v1-9c2f4d7ab31e", AI_PROVIDERS.first().baseUrl, "google/gemma-4-31b-it:free")
    KeyScreen(
        config = config,
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        keyCheck = KeyCheck.Works("google/gemma-4-31b-it:free", tookMs = 1_240, checked = keyFingerprint(config)),
    )
}

@Preview(name = "Настройки · ключ не принят (#447)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsKeyRejected() = PointTheme(darkTheme = true) {
    // Отказ не сглажен: код провайдера, его слова и то, что человеку с этим делать.
    val config = UserAiConfig("sk-or-v1-9c2f4d7ab31e", AI_PROVIDERS[1].baseUrl, "llama-3.3-70b-versatile")
    KeyScreen(
        config = config,
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        keyCheck = KeyCheck.Rejected(
            "Groq не принял ключ (401). Чаще всего это опечатка или скопирована половина. " +
                "Ответ: {\"error\":{\"message\":\"Invalid API Key\"}}",
            checked = keyFingerprint(config),
        ),
    )
}

@Preview(name = "Настройки · куда можно отправлять (#280)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewSettingsPrivacy() = PointTheme(darkTheme = true) {
    // Нижняя часть экрана: выбран «Только Европа». Цена стоит при каждом варианте — видно, что
    // человек теряет и что получает, не выбирая их по очереди.
    KeyScreen(
        config = UserAiConfig("sk-demo-key-1234", AI_PROVIDERS[1].baseUrl, "llama-3.3-70b-versatile"),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        privacyLevel = PrivacyLevel.EUROPE_ONLY,
    )
}
