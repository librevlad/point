package com.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiProvider
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PRIVACY_SETTING_TITLE
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.flow.providerForBaseUrl
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SectionLabel
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointTheme

/**
 * Bring-your-own AI key. Point runs on the user's key and quota, so a released
 * build is safe to hand out. Summoned on demand (AI with no key) or from the Home
 * gear — not a persistent settings menu.
 *
 * Приведён к языку портала (#114). Провайдер выглядит строкой действия, и выбранный — той самой
 * светящейся строкой, которой на экране объекта отмечено основное действие: «вот этот». Раньше
 * выбор показывался двумя выдуманными тут же полупрозрачностями (`primaryContainer` 45% против
 * `surfaceVariant` 35%) — цветами, которых нет больше нигде в приложении.
 *
 * «Сохранить» — та же светящаяся строка; тихое — текстом. Разделительной линии Material больше нет:
 * тумблеры стоят карточками портала, и группу видно без черты. Счётчик уехал внутрь своей карточки
 * — он и есть итог того тумблера, а не отдельная строка внизу экрана.
 *
 * «Куда можно отправлять» (#280) собрано тем же строем, что выбор провайдера, — и по той же
 * причине: это тот же вопрос «выбери одно из нескольких». Полоска узких чипов показывала цену
 * только выбранного варианта; список строк держит цену при **каждом**.
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
    /** Разрешена ли отправка объектов моделям — и здесь же её можно забрать обратно (#114). */
    cloudEnabled: Boolean = false,
    onToggleCloud: (Boolean) -> Unit = {},
    /** Открыть страницу провайдера, где выдают ключ (#403). */
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var key by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }

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
                title = "Ваш AI-ключ",
                subtitle = "Point работает на вашем ключе и вашей квоте — чужие ключи он не хранит и не просит.",
                modifier = Modifier.padding(bottom = 9.dp),
            )

            // Выбор провайдера вместо трёх полей наизусть: адрес и модель подставляются сами, а
            // рядом лежит ссылка на страницу, где ключ выдают. Раньше человек должен был знать
            // «endpoint (base URL)» — это знание разработчика, а не пользователя.
            var chosen by remember(config) { mutableStateOf(providerForBaseUrl(config.baseUrl)) }
            SectionLabel("Откуда взять ключ")
            AI_PROVIDERS.forEachIndexed { index, provider ->
                ProviderRow(
                    provider = provider,
                    selected = chosen?.id == provider.id,
                    index = index,
                    onChoose = {
                        chosen = provider
                        baseUrl = provider.baseUrl
                        model = provider.models.substringBefore(',')
                    },
                    onOpenUrl = onOpenUrl,
                )
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API-ключ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Адрес остаётся видимым и правимым: у кого-то свой прокси, и отнимать эту возможность
            // ради красоты нельзя. Но набирать его с нуля больше не нужно.
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Адрес сервиса") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))
            // «Основное действие» экрана — та же светящаяся строка, что главное действие объекта.
            // Пока ключа нет, она притушена: строка светится, когда может.
            val canSave = key.isNotBlank()
            PortalRow(
                title = "Сохранить",
                onClick = { onSave(UserAiConfig(key.trim(), baseUrl.trim(), model.trim())) },
                icon = bubbleIcon("save"),
                primary = true,
                chevron = false,
                enabled = canSave,
                modifier = Modifier.graphicsLayer { alpha = if (canSave) 1f else 0.45f },
            )

            Spacer(Modifier.height(14.dp))
            // Отозвать разрешение было негде: человек, разрешивший облако одним тапом когда-то, не
            // мог передумать ничем, кроме переустановки (#114). Тумблер стоит первым в этом ряду —
            // это самое дорогое из здешних решений: им объект уезжает с устройства. Соседняя
            // настройка «Куда можно отправлять» (#280) отвечает на другой вопрос — кому МОЖНО
            // предлагать; этот тумблер отвечает, разрешено ли вообще отпускать объект.
            SwitchCard(
                title = "Отправка в облако",
                description = "Разрешает показывать объект моделям — по вашему тапу и с названием того, " +
                    "куда он уедет. Выключите, и Point спросит заново. Выложить файл по открытой " +
                    "ссылке этим тумблером нельзя: про такое спрашивают каждый раз.",
                checked = cloudEnabled,
                onCheckedChange = onToggleCloud,
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
            SwitchCard(
                title = "Звук действий",
                description = "Тихий фирменный отклик на каждое действие. Вибрация управляется системной настройкой касаний.",
                checked = soundEnabled,
                onCheckedChange = onToggleSound,
            )

            // «Куда можно отправлять» (#280) — тем же строем, что выбор провайдера выше: список
            // строк, где выбранная светится. Три уровня не влезали в один переключатель, и первым
            // решением была полоска узких чипов с ценой выбранного отдельной строкой ниже. Здесь у
            // экрана уже есть готовый ответ на ровно этот вопрос — «выбери одно из нескольких», — и
            // у него есть свойство, которого у чипов нет: цена стоит при **каждом** варианте, а не
            // только при том, который человек уже выбрал.
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
        }

        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onCancel) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Провайдер в списке: имя, чем он хорош, что известно про бесплатность — и ссылка на страницу,
 * где ключ выдают.
 *
 * Ссылка отдельной кнопкой, а не текстом: «сходить за ключом» и «выбрать этого» — разные желания,
 * и склеивать их в один тап значит промахиваться в половине случаев.
 */
@Composable
private fun ProviderRow(
    provider: AiProvider,
    selected: Boolean,
    index: Int,
    onChoose: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    PortalRow(
        title = provider.name,
        subtitle = listOfNotNull(provider.what, provider.freeNote).joinToString(" · "),
        onClick = onChoose,
        icon = bubbleIcon(AI_ICON),
        accent = bubbleColor(AI_ICON),
        primary = selected,
        appearIndex = index,
        trailing = {
            TextButton(onClick = { onOpenUrl(provider.keyUrl) }) {
                Text(
                    text = "Взять ключ",
                    color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                )
            }
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(name = "Ключ AI · провайдер выбран (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreen() = PointTheme(darkTheme = true) {
    KeyScreen(
        config = UserAiConfig(apiKey = "", baseUrl = AI_PROVIDERS.first().baseUrl, model = ""),
        onSave = {},
        onCancel = {},
        usageEnabled = true,
        usageSummary = UsageSummary(objects = 42, actions = 118, completed = 31),
        onToggleUsage = {},
    )
}

@Preview(name = "Ключ AI · ключа ещё нет (#114)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenEmpty() = PointTheme(darkTheme = true) {
    // Пустой ключ: «Сохранить» стоит на месте, но не светится — строка светится, когда может.
    KeyScreen(
        config = UserAiConfig(apiKey = "", baseUrl = "", model = ""),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
    )
}

@Preview(name = "Ключ AI · куда можно отправлять (#280 в системе)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewKeyScreenPrivacy() = PointTheme(darkTheme = true) {
    // Нижняя часть экрана: выбран «Только Европа». Цена стоит при каждом варианте — видно, что
    // человек теряет и что получает, не выбирая их по очереди.
    KeyScreen(
        config = UserAiConfig(apiKey = "sk-demo", baseUrl = AI_PROVIDERS[1].baseUrl, model = ""),
        onSave = {},
        onCancel = {},
        usageEnabled = false,
        usageSummary = null,
        onToggleUsage = {},
        privacyLevel = PrivacyLevel.EUROPE_ONLY,
    )
}
