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

/** Чем расшифровывается запись: тем же ключом из очереди, а не отдельным полем (#912). */
private fun speechSaysWhere(keys: com.point.core.flow.UserAiKeys): String {
    val mine = com.point.core.flow.speechKeyFromChain(keys)
    val who = com.point.core.flow.speechProviderNames()
    return if (mine == null) {
        "Расшифровка записи работает на ключе $who — впишите его выше, в очереди."
    } else {
        "Расшифровка записи работает на вашем ключе " +
            (com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == mine.providerId }?.name ?: who) +
            " — том же, что в очереди выше."
    }
}

/**
 * Раздел «Ключи AI» в окне компьютера (#834, #911).
 *
 * Тот же экран, что на телефоне: объяснение → сводка → очередь сервисов → местные
 * ключи. Файл на раздел — как и в настройках телефона.
 */
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
        // Сводка — один и тот же блок, что на телефоне: состояние, время последней проверки
        // и — там, где оно есть, — действие. Здесь его нет, и об этом сказано прямо, а не
        // умолчанием (#911).
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PointColors.surface)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(com.point.core.flow.aiKeysCount(keys), style = PointType.body)
            Text(
                com.point.core.flow.AI_CHECK_ELSEWHERE,
                style = PointType.small.copy(color = PointColors.muted),
            )
        }

        com.point.core.flow.aiServiceGroups(lines).forEach { (group, rows) ->
            Text(group.title.uppercase(), style = PointType.label)
            if (group == com.point.core.flow.AiServiceGroup.MINE) {
                Text(
                    com.point.core.flow.AI_MINE_KEEP_PLACE,
                    style = PointType.small.copy(color = PointColors.muted),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { line ->
                    PortalRow(
                        // Номер — место в очереди обращения, то же, что на телефоне (#902).
                        // Он вторичен: главное в строке — имя сервиса (#911).
                        title = line.name,
                        place = line.place,
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

        // Своя секция, а не продолжение очереди: это местные умения компьютера, а не
        // очередные сервисы AI (#911).
        //
        // Отдельного «ключа расшифровки» здесь больше не спрашивают (#912): на телефоне его
        // нет вовсе — расшифровка берёт ключ Groq из этой же очереди, — а тут человека
        // просили вписать второй раз то, что он уже вписал выше, и не говорили откуда взять.
        Spacer(Modifier.height(6.dp))
        Text("ДЛЯ ЗАПИСЕЙ И СНИМКОВ", style = PointType.label)
        Text(
            speechSaysWhere(keys),
            style = PointType.small.copy(color = PointColors.muted),
        )
        Text(
            "Снимки читает OCR.space. Он работает и без вашего ключа — на общем; свой снимает " +
                "его дневной предел.",
            style = PointType.small.copy(color = PointColors.muted),
        )
        Field(ocrKey, { ocrKey = it; store() }, "Ваш ключ OCR.space — необязателен", secret = true)
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
