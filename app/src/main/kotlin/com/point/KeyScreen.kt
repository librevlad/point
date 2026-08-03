package com.point

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig

/**
 * Bring-your-own AI key. Point runs on the user's key and quota, so a released
 * build is safe to hand out. Summoned on demand (AI with no key) or from the Home
 * gear — not a persistent settings menu.
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ваш AI-ключ", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Point работает на вашем ключе и вашей квоте — чужие ключи он не хранит и не просит.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Выбор провайдера вместо трёх полей наизусть: адрес и модель подставляются сами, а
        // рядом лежит ссылка на страницу, где ключ выдают. Раньше человек должен был знать
        // «endpoint (base URL)» — это знание разработчика, а не пользователя.
        var chosen by remember(config) {
            mutableStateOf(com.point.core.flow.providerForBaseUrl(config.baseUrl))
        }
        Text("Откуда взять ключ", style = MaterialTheme.typography.titleSmall)
        com.point.core.flow.AI_PROVIDERS.forEach { provider ->
            ProviderRow(
                provider = provider,
                selected = chosen?.id == provider.id,
                onChoose = {
                    chosen = provider
                    baseUrl = provider.baseUrl
                    model = provider.models.substringBefore(',')
                },
                onOpenUrl = onOpenUrl,
            )
        }

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

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            Button(
                onClick = { onSave(UserAiConfig(key.trim(), baseUrl.trim(), model.trim())) },
                enabled = key.isNotBlank(),
            ) { Text("Сохранить") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Приватная статистика", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Обезличенно, только на устройстве — мерит, экономит ли Point переключения между приложениями.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = usageEnabled, onCheckedChange = onToggleUsage)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Звук действий", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Тихий фирменный отклик на каждое действие. Вибрация управляется системной настройкой касаний.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = soundEnabled, onCheckedChange = onToggleSound)
        }
        if (usageEnabled && usageSummary != null) {
            Text(
                "Объектов: ${usageSummary.objects} · действий: ${usageSummary.actions} · завершено в Point: ${usageSummary.completed}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    provider: com.point.core.flow.AiProvider,
    selected: Boolean,
    onChoose: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onChoose),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    provider.what,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                provider.freeNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = { onOpenUrl(provider.keyUrl) }) { Text("Взять ключ") }
        }
    }
}
