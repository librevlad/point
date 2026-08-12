package com.point.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.point.desktop.AiConfig
import com.point.desktop.DesktopAiKeyCheck
import com.point.desktop.PcConfig
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    config: PcConfig,
    onSave: (PcConfig) -> Unit,
    onSweepNow: () -> Unit,
    onClose: () -> Unit,

    /** Проверка ключа — та же, что на телефоне (#610). */
    keyCheck: com.point.core.flow.AiKeyCheck = DesktopAiKeyCheck(),
    onOpenUrl: (String) -> Unit = {},
) {
    var name by remember { mutableStateOf(config.name) }
    var aiKey by remember { mutableStateOf(config.ai.key) }
    var speechKey by remember { mutableStateOf(config.speech.key) }
    var ocrKey by remember { mutableStateOf(config.ocr.key) }
    var server by remember { mutableStateOf(config.server) }
    var rightClick by remember { mutableStateOf(config.rightClick) }
    var swept by remember { mutableStateOf<Int?>(null) }
    var aiUrl by remember { mutableStateOf(config.ai.url) }
    var aiModel by remember { mutableStateOf(config.ai.model) }
    var verdict by remember { mutableStateOf<KeyVerdict?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun store() = onSave(
        config.copy(
            name = name.trim().ifBlank { config.name },
            server = server.trim(),
            rightClick = rightClick,
            ai = config.ai.copy(key = aiKey.trim(), url = aiUrl, model = aiModel),
            speech = config.speech.copy(key = speechKey.trim()),
            ocr = config.ocr.copy(key = ocrKey.trim()),
        ),
    )

    // Скролл отдан единственному хозяину — компакт-настройкам: свой verticalScroll
    // внутри их скролла ронял окно (краш владельца 2026-08-09, «настройки»).
    Column(
        modifier = Modifier.width(560.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("НАСТРОЙКИ", style = PointType.label)

        Group("Этот компьютер", "Имя видят ваши другие устройства") {
            Field(name, { name = it; store() }, "Рабочий ноутбук")
        }

        Group(
            "Ключи сервисов",
            "Обычно вписывать их здесь не нужно: ключ, введённый на телефоне, приезжает сюда сам",
        ) {
            // Сервис называется своим именем и говорит, для чего он, — теми же словами, что и
            // на телефоне (#610): голое поле «Ключ AI» не сообщало ни того, ни другого.
            AI_PROVIDERS.forEach { provider ->
                Service(provider, chosen = chosenFor(aiUrl, provider)) {
                    aiUrl = provider.baseUrl
                    aiModel = provider.models.substringBefore(',')
                    verdict = null
                    store()
                }
            }
            Field(aiKey, { aiKey = it; verdict = null; store() }, "Ключ выбранного сервиса", secret = true)

            Action(if (checking) "Проверяю…" else "Проверить ключ") {
                if (!checking && looksLikeApiKey(aiKey)) {
                    checking = true
                    scope.launch {
                        val probe = keyCheck.check(
                            UserAiConfig(apiKey = aiKey.trim(), baseUrl = aiUrl, model = aiModel),
                        )
                        verdict = keyVerdict(probe)
                        checking = false
                    }
                }
            }
            verdict?.let { Verdict(it) }

            Field(speechKey, { speechKey = it; store() }, "Ключ расшифровки речи", secret = true)
            Field(ocrKey, { ocrKey = it; store() }, "Ключ чтения снимков — необязателен", secret = true)
        }

        Group(
            "Присланное",
            "Point не хранит дольше суток: присланное с телефона и сделанное здесь убирается само. " +
                "Файл, который вы перетащили мышью, не трогается никогда",
        ) {
            Action(swept?.let { "Убрано: $it" } ?: "Убрать прямо сейчас") { swept = null; onSweepNow() }
        }

        Group(
            "Правая кнопка",
            "«Открыть в Point» и «Отправить → Point» в контекстном меню любого файла — то, ради " +
                "чего Point и стоит на компьютере. Записи делаются только для вас и снимаются " +
                "вместе с этой галкой",
        ) {
            Action(if (rightClick) "Показывать · выключить" else "Не показывать · включить") {
                rightClick = !rightClick
                store()
            }
        }

        Group("Сервер", "Пусто — сервер Point. Свой адрес нужен, только если вы поднимаете его сами") {
            Field(server, { server = it; store() }, "https://point.leerio.app")
        }

        // Версия видна человеку, а не только в свойствах файла (#822): падение из-за старой
        // установки перестаёт быть загадкой — «у меня от шестого августа» видно сразу.
        Text(
            "Point ${com.point.desktop.BuildInfo.VERSION} · сборка ${com.point.desktop.BuildInfo.BUILT_ON}",
            style = PointType.small.copy(color = PointColors.muted),
        )

        Action("Закрыть", onClose)
    }
}

/** Выбран ли сервис: адрес вызова у компьютера полный, у списка — база. */
internal fun chosenFor(url: String, provider: AiProvider): Boolean =
    url.trim().trimEnd('/').startsWith(provider.baseUrl.trimEnd('/'))

@Composable
private fun Service(provider: AiProvider, chosen: Boolean, onChoose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (chosen) PointColors.violet else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onChoose)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(provider.name, style = PointType.body)
            Text(provider.what, style = PointType.small.copy(color = PointColors.muted))
        }
        provider.freeNote?.let { Text(it, style = PointType.small) }
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

@Composable
private fun Group(title: String, hint: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = PointType.title)
        Text(hint, style = PointType.small)
        content()
    }
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
            visualTransformation = if (secret && value.isNotEmpty()) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(PointColors.surface, PointColors.surfaceDeep)))
                .border(1.dp, PointColors.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun Action(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = PointType.body)
    }
}
