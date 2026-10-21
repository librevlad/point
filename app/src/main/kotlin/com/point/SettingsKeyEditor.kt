package com.point

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiServiceLine
import com.point.core.flow.KeyVerdict
import com.point.core.flow.NO_KEY_IN_CLIPBOARD
import com.point.core.flow.UserAiKey
import com.point.core.flow.checkAsKeyOf
import com.point.core.flow.keyOfAnotherService
import com.point.core.flow.keyWorksLine
import com.point.core.flow.looksLikeApiKey
import com.point.core.flow.providerOfKey
import com.point.core.model.tr
import com.point.core.ui.GlassFieldShape
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeCard
import com.point.core.ui.PortalRow
import com.point.core.ui.bubbleColor
import com.point.core.ui.bubbleIcon
import com.point.core.ui.glassFieldColors
import com.point.core.ui.portalCard
import com.point.core.ui.theme.PointText
import kotlinx.coroutines.delay

/**
 * Что раскрывается под сервисом в «Ключах AI»: сайт, поле ключа, проверка и её приговор.
 *
 * Отдельным файлом — раздел ключей перерос предел файла настроек (#834): список и вставка
 * сверху живут в `SettingsKeysSection.kt`, редактор одного сервиса — здесь.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServiceEditor(
    line: AiServiceLine,
    saved: UserAiKey?,
    pasted: String?,
    checking: Boolean,
    verdict: KeyVerdict?,
    errand: KeyErrand?,
    onSave: (UserAiKey) -> Unit,
    onCheck: (UserAiKey) -> Unit,
    onPasteKey: () -> String?,
    onForgetKey: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLeave: () -> Unit,
    onElsewhere: (key: String, providerId: String) -> Unit,
) {
    val provider = AI_PROVIDERS.firstOrNull { it.id == line.providerId }
    val draft = rememberSaveable(line.providerId, saved, pasted, saver = KeyDraft.Saver) {
        val start = saved ?: UserAiKey(line.providerId, "")
        KeyDraft(if (pasted != null) start.copy(apiKey = pasted) else start)
    }
    val account = provider?.account
    val canCheck = draft.key.isNotBlank() && !checking && (account == null || draft.account.isNotBlank())

    // Приговор встаёт там, куда смотрят: удачный ключ переезжает в «Ваши ключи», и без этого
    // «Работает» оказывалось за краем экрана, а человек не знал, принят ли ключ.
    val verdictView = remember { BringIntoViewRequester() }
    LaunchedEffect(verdict) {
        if (verdict != null) {
            delay(SETTLE_MS)
            verdictView.bringIntoView()
        }
    }
    // Ключ, вставленный сверху, открывает свой сервис где-то ниже — туда и ведём, пока
    // приговора ещё нет; когда он есть, смотреть надо на него.
    val editorView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (pasted != null && verdict == null) {
            delay(SETTLE_MS)
            editorView.bringIntoView()
        }
    }

    Column(
        modifier = Modifier
            .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
            .bringIntoViewRequester(editorView)
            .portalCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (provider != null) {
            PortalRow(
                title = tr("Открыть сайт {0}", provider.name),
                subtitle = listOfNotNull(
                    offerSentence(provider),
                    tr("Там выдают ключ: заведите аккаунт, скопируйте ключ — и вернитесь сюда."),
                ).joinToString(" "),
                onClick = { onOpenUrl(provider.keyUrl) },
                icon = bubbleIcon("open"),
                accent = bubbleColor("open"),
                chevron = false,
                subtitleMaxLines = 4,
            )
        }

        OutlinedTextField(
            value = draft.key,
            onValueChange = {
                draft.key = it
                draft.pasteNote = ""
                draft.elsewhere = ""
            },
            label = { Text(tr("Ключ {0}", line.name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            // Вставленный ключ можно увидеть: точки не говорят, тот ли ключ встал и целиком ли.
            visualTransformation = if (draft.revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = if (draft.key.isNotBlank()) {
                {
                    TextButton(onClick = { draft.revealed = !draft.revealed }) {
                        Text(if (draft.revealed) tr("Скрыть") else tr("Показать"), style = PointText.note)
                    }
                }
            } else {
                null
            },
            shape = GlassFieldShape,
            colors = glassFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (account != null) {
            OutlinedTextField(
                value = draft.account,
                onValueChange = { draft.account = it.trim() },
                label = { Text(account.label) },
                supportingText = { Text(account.where) },
                singleLine = true,
                shape = GlassFieldShape,
                colors = glassFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.key.isBlank()) {
            PortalRow(
                title = tr("Вставить из буфера"),
                subtitle = tr("Скопировали ключ на странице сервиса — он встанет сюда и сразу проверится."),
                onClick = {
                    val pastedNow = onPasteKey()?.trim()
                    if (pastedNow == null || !looksLikeApiKey(pastedNow)) {
                        draft.pasteNote = NO_KEY_IN_CLIPBOARD
                    } else {
                        draft.key = pastedNow
                        draft.pasteNote = ""
                        val other = providerOfKey(pastedNow)?.takeIf { it.id != line.providerId }
                        draft.elsewhere = other?.id.orEmpty()
                        val ready = account == null || draft.account.isNotBlank()
                        if (other == null && ready && !checking) onCheck(draft.entered())
                    }
                },
                icon = bubbleIcon("copy"),
                chevron = false,
                subtitleMaxLines = 3,
            )
        }
        if (draft.pasteNote.isNotEmpty()) {
            Text(draft.pasteNote, style = PointText.note)
        }

        // Ключ другого сервиса узнаётся по началу: Point говорит это до проверки, а не
        // объявляет верный ключ негодным.
        AI_PROVIDERS.firstOrNull { it.id == draft.elsewhere }?.let { other ->
            Text(keyOfAnotherService(other.name, line.name), style = PointText.note)
            PortalRow(
                title = checkAsKeyOf(other.name),
                subtitle = tr("Ключ встанет к {0} и проверится там.", other.name),
                onClick = { onElsewhere(draft.key, other.id) },
                icon = bubbleIcon(AI_ICON),
                chevron = false,
            )
        }

        DisclosureRow(
            title = if (account == null) tr("Модель и адрес") else tr("Модель"),
            subtitle = listOf(draft.model, if (account == null) draft.baseUrl else "")
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { tr("как у сервиса — набирать не нужно") },
            open = draft.advancedOpen,
            onToggle = { draft.advancedOpen = !draft.advancedOpen },
        )
        Reveal(draft.advancedOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft.model = it },
                    label = { Text(tr("Модель")) },
                    singleLine = true,
                    shape = GlassFieldShape,
                    colors = glassFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // У сервиса с номером аккаунта адрес собирается из номера: второе поле адреса
                // спорило бы с ним.
                if (account == null) {
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft.baseUrl = it },
                        label = { Text(tr("Адрес сервиса")) },
                        singleLine = true,
                        shape = GlassFieldShape,
                        colors = glassFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        PortalRow(
            title = if (checking) tr("Проверяю…") else tr("Проверить и включить"),
            subtitle = tr("Point спросит сервис одним коротким словом. Ваш объект при этом никуда не отправляется."),
            onClick = { onCheck(draft.entered()) },
            icon = bubbleIcon(AI_ICON),
            primary = verdict !is KeyVerdict.Works,
            chevron = false,
            enabled = canCheck,
            subtitleMaxLines = 3,
            modifier = Modifier.graphicsLayer { alpha = if (canCheck) 1f else 0.45f },
        )

        // Приговор — сразу под кнопкой, которая его вызвала, а не под «Удалить ключ».
        when (verdict) {
            is KeyVerdict.Works -> OutcomeCard(
                title = keyWorksLine(verdict),
                detail = tr("Теперь «Понять», «Перевести», «Спросить AI» и расшифровка записи работают."),
                outcome = Outcome.DONE,
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(verdictView),
            )
            is KeyVerdict.Refused -> OutcomeCard(
                title = verdict.what,
                detail = verdict.fix,
                outcome = Outcome.FAILED,
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(verdictView),
            )
            null -> Unit
        }

        if (errand != null && verdict is KeyVerdict.Works) ReturnDoor(errand, onLeave)

        if (draft.key.isNotBlank() && verdict !is KeyVerdict.Works) {
            TextButton(onClick = { onSave(draft.entered()) }) {
                Text(tr("Сохранить без проверки"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (line.mine) {
            PortalRow(
                title = tr("Удалить ключ"),
                subtitle = tr("Point сотрёт его с устройства. {0} снова замолчит, пока не впишете новый.", line.name),
                onClick = {
                    draft.key = ""
                    draft.pasteNote = ""
                    draft.elsewhere = ""
                    onForgetKey(line.providerId)
                },
                chevron = false,
                subtitleMaxLines = 3,
            )
        }
    }
}

/** Пауза, пока строка переезжает в свою группу и раскрывается: потом её видно целиком. */
private const val SETTLE_MS = 250L
