package com.point.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFact
import com.point.core.flow.KeyProbe
import com.point.core.flow.KeyVerdict
import com.point.core.flow.NO_KEY_IN_CLIPBOARD
import com.point.core.flow.PASTE_ANY_KEY
import com.point.core.flow.PASTE_ANY_KEY_WHAT
import com.point.core.flow.WHOSE_KEY_DISMISS
import com.point.core.flow.WHOSE_KEY_TITLE
import com.point.core.flow.WHOSE_KEY_WHAT
import com.point.core.flow.checkAsKeyOf
import com.point.core.flow.keyOfAnotherService
import com.point.core.flow.UserAiKey
import com.point.core.flow.accountIn
import com.point.core.flow.aiOutcomeOfStatus
import com.point.core.flow.keyVerdict
import com.point.core.flow.looksLikeApiKey
import com.point.core.flow.providerOfKey
import com.point.core.flow.urlWithAccount
import com.point.core.flow.withoutKey
import com.point.core.model.tr
import com.point.desktop.PcConfig
import kotlinx.coroutines.launch

/** Чем расшифровывается запись: тем же ключом из очереди, а не отдельным полем (#912). */
private fun speechSaysWhere(keys: com.point.core.flow.UserAiKeys): String {
    val mine = com.point.core.flow.speechKeyFromChain(keys)
    val who = com.point.core.flow.speechProviderNames()
    return if (mine == null) {
        tr("Расшифровка записи работает на ключе {0} — впишите его выше, в очереди.", who)
    } else {
        tr("Расшифровка записи работает на вашем ключе ") +
            (com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == mine.providerId }?.name ?: who) +
            tr(" — том же, что в очереди выше.")
    }
}

/** Текст из буфера обмена — только по нажатию человека, как и на телефоне (#677). */
internal fun clipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()

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
 *
 * Дорога та же, что на телефоне: ключ из буфера встаёт к своему сервису сам и сразу
 * проверяется, а сохраняется — только когда сервис ответил.
 */
@Composable
fun SettingsKeys(
    config: PcConfig,
    onSave: (PcConfig) -> Unit,
    keyCheck: com.point.core.flow.AiKeyCheck =
        com.point.core.flow.HttpAiKeyCheck(com.point.core.flow.UrlConnectionHttpJson()),

    // Без значения по умолчанию: с пустым `{}` окно настроек его не передавало, и «Открыть
    // сайт …» на компьютере молча не делало ничего (#1487).
    onOpenUrl: (String) -> Unit,
    readClipboard: () -> String? = ::clipboardText,
) {
    var keys by remember { mutableStateOf(config.aiKeys) }
    var speechKey by remember { mutableStateOf(config.speech.key) }
    var ocrKey by remember { mutableStateOf(config.ocr.key) }
    var open by remember { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf<KeyVerdict?>(null) }
    var verdictFor by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf<String?>(null) }

    // Исходы проверок — память окна: по ним строка говорит «лимит исчерпан», а сводка —
    // сколько сервисов отвечают. Своих ключей у Point на компьютере нет, поэтому проверять
    // здесь есть что только у ключей человека.
    var facts by remember { mutableStateOf(emptyMap<String, AiFact>()) }

    var unclaimed by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf<UserAiKey?>(null) }
    var pasteNote by remember { mutableStateOf<String?>(null) }
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

    suspend fun probe(key: UserAiKey): KeyVerdict {
        val call = com.point.core.flow.aiCall(key)
        val said = runCatching { keyCheck.check(call) }
            .getOrElse { KeyProbe(error = withoutKey(it.message.orEmpty(), call.apiKey)) }
        facts = facts + (key.providerId to AiFact(aiOutcomeOfStatus(said.status), System.currentTimeMillis()))
        return keyVerdict(said)
    }

    // Проверка — до сохранения, как на телефоне. Прежде ключ сохранялся, а после отказа
    // стирался (#1080): сорвавшаяся связь или исчерпанный лимит стирали и верный ключ.
    fun check(key: UserAiKey) {
        if (checking != null || !looksLikeApiKey(key.apiKey)) return
        checking = key.providerId
        verdict = null
        verdictFor = null
        scope.launch {
            val said = probe(key)
            verdict = said
            verdictFor = key.providerId
            checking = null
            if (said is KeyVerdict.Works) store(keys.with(key))
        }
    }

    fun checkAll() {
        if (checking != null) return
        checking = CHECK_ALL
        verdict = null
        verdictFor = null
        scope.launch {
            keys.mine.forEach { probe(it) }
            checking = null
        }
    }

    fun take(key: String, providerId: String) {
        val provider = AI_PROVIDERS.firstOrNull { it.id == providerId }
        val entered = (keys.of(providerId) ?: UserAiKey(providerId, "")).copy(apiKey = key.trim())
        unclaimed = null
        pasteNote = null
        pasted = entered
        open = providerId
        val waitsForAccount = provider?.account != null && provider.accountIn(entered.baseUrl).isEmpty()
        if (!waitsForAccount) check(entered)
    }

    val now = System.currentTimeMillis()
    val lines = com.point.core.flow.aiServiceLines(keys, emptySet(), facts, now)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Панель состояния, а не документация (#902): наверху две мысли, подробности —
        // за «Как это работает».
        Text(com.point.core.flow.AI_CHAIN_WHAT, style = PointType.small)
        var howOpen by remember { mutableStateOf(false) }
        Text(
            if (howOpen) tr("Свернуть") else tr("Как это работает"),
            style = PointType.small.copy(color = PointColors.link),
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { howOpen = !howOpen }
                .padding(vertical = 3.dp),
        )
        if (howOpen) {
            Text(
                com.point.core.flow.AI_CHAIN_MORE + tr(" Ключи общие с телефоном: вписанный " +
                    "здесь появится там, и наоборот."),
                style = PointType.small.copy(color = PointColors.muted),
            )
        }

        // Ключ из буфера — к своему сервису, без поиска строки среди двенадцати.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(PASTE_ANY_KEY) {
                if (checking != null) return@Action
                val text = readClipboard()?.trim()
                when {
                    text == null || !looksLikeApiKey(text) -> {
                        unclaimed = null
                        pasteNote = NO_KEY_IN_CLIPBOARD
                    }
                    else -> providerOfKey(text)?.let { take(text, it.id) } ?: run {
                        pasteNote = null
                        unclaimed = text
                    }
                }
            }
            Text(PASTE_ANY_KEY_WHAT, style = PointType.small.copy(color = PointColors.muted))
        }
        pasteNote?.let { Text(it, style = PointType.small.copy(color = PointColors.muted)) }
        unclaimed?.let { key -> WhoseKey(onPick = { take(key, it) }, onDismiss = { unclaimed = null }) }

        // Сводка — тот же блок, что на телефоне: счёт своих ключей, итог проверки и сама
        // проверка. Прежде проверять было нечем: «Проверка цепочки выполняется на телефоне».
        Row(
            modifier = Modifier.fillMaxWidth()
                .glassPanel(RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(com.point.core.flow.aiKeysCount(keys), style = PointType.body)
                Text(
                    if (checking == CHECK_ALL) {
                        tr("Point спрашивает каждый сервис одним коротким словом.")
                    } else {
                        com.point.core.flow.aiChainLine(lines, facts, now)
                    },
                    style = PointType.small.copy(color = PointColors.muted),
                )
            }
            if (keys.mine.isNotEmpty()) {
                Action(if (checking == CHECK_ALL) tr("Проверяю…") else tr("Проверить все")) { checkAll() }
            }
        }

        com.point.core.flow.aiServiceGroups(lines).forEach { (group, rows) ->
            Text(group.title.uppercase(), style = PointType.label)
            if (group == com.point.core.flow.AiServiceGroup.MINE) {
                Text(
                    com.point.core.flow.AI_MINE_KEEP_PLACE,
                    style = PointType.small.copy(color = PointColors.muted),
                )
            }
            Column(Modifier.fillMaxWidth().glassPanel().padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { line ->
                    val isOpen = open == line.providerId
                    PortalRow(
                        // Номер — место в очереди обращения, то же, что на телефоне (#902).
                        // Он вторичен: главное в строке — имя сервиса (#911).
                        title = line.name,
                        place = line.place,
                        subtitle = com.point.core.flow.aiServiceState(line, checking == line.providerId, isOpen),
                        onClick = { open = if (isOpen) null else line.providerId },
                    )
                    if (isOpen) {
                        ServiceKey(
                            line = line,
                            saved = keys.of(line.providerId),
                            pasted = pasted?.takeIf { it.providerId == line.providerId },
                            checking = checking == line.providerId,
                            verdict = verdict.takeIf { verdictFor == line.providerId },
                            onOpenUrl = onOpenUrl,
                            onSave = { key -> verdict = null; store(keys.with(key)) },
                            onForget = { store(keys.without(line.providerId)) },
                            onCheck = ::check,
                            onElsewhere = ::take,
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
        Text(tr("ДЛЯ ЗАПИСЕЙ И СНИМКОВ"), style = PointType.label)
        Text(
            speechSaysWhere(keys),
            style = PointType.small.copy(color = PointColors.muted),
        )
        Text(
            tr("Снимки читает OCR.space. Он работает и без вашего ключа — на общем; свой снимает " +
                "его дневной предел."),
            style = PointType.small.copy(color = PointColors.muted),
        )
        Field(ocrKey, { ocrKey = it; store() }, tr("Ваш ключ OCR.space — необязателен"), secret = true, revealable = true)
    }
}

/** «Проверить все» идёт — ни одна строка не проверяется отдельно. */
private const val CHECK_ALL = "*"

/**
 * Ключ без приметы сервиса: спросить, а не гадать. Ключ одного сервиса, отправленный на
 * пробу другому, — это чужой секрет в чужих руках.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoseKey(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .glassPanel(RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(WHOSE_KEY_TITLE, style = PointType.body)
        Text(WHOSE_KEY_WHAT, style = PointType.small.copy(color = PointColors.muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AI_PROVIDERS.forEach { provider -> PillButton(provider.name, { onPick(provider.id) }, small = true) }
        }
        Action(WHOSE_KEY_DISMISS, onDismiss)
    }
}

/** Что открывается под сервисом: сайт, поле ключа, проверка. */
@Composable
private fun ServiceKey(
    line: com.point.core.flow.AiServiceLine,
    saved: UserAiKey?,
    pasted: UserAiKey?,
    checking: Boolean,
    verdict: KeyVerdict?,
    onOpenUrl: (String) -> Unit,
    onSave: (UserAiKey) -> Unit,
    onForget: () -> Unit,
    onCheck: (UserAiKey) -> Unit,
    onElsewhere: (key: String, providerId: String) -> Unit,
) {
    val provider = AI_PROVIDERS.firstOrNull { it.id == line.providerId }
    val start = pasted ?: saved
    var draft by remember(line.providerId, saved, pasted) { mutableStateOf(start?.apiKey.orEmpty()) }
    var number by remember(line.providerId, saved, pasted) {
        mutableStateOf(provider?.accountIn(start?.baseUrl.orEmpty()).orEmpty())
    }
    val account = provider?.account

    fun entered() = UserAiKey(
        providerId = line.providerId,
        apiKey = draft.trim(),
        model = saved?.model.orEmpty(),
        baseUrl = if (account != null && number.isNotBlank()) provider.urlWithAccount(number) else saved?.baseUrl.orEmpty(),
        savedAt = saved?.savedAt ?: 0L,
    )

    // Ключ другого сервиса узнаётся по началу — сказать до проверки, а не объявить верный
    // ключ негодным.
    val elsewhere = providerOfKey(draft)?.takeIf { it.id != line.providerId }
    val canCheck = draft.isNotBlank() && !checking && (account == null || number.isNotBlank())

    Column(
        modifier = Modifier.fillMaxWidth()
            .glassPanel(RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        provider?.freeNote?.let { Text(it, style = PointType.small.copy(color = PointColors.cyan)) }
        provider?.let { Action(tr("Открыть сайт {0}", it.name)) { onOpenUrl(it.keyUrl) } }

        Field(draft, { draft = it }, tr("Ключ {0}", line.name), secret = true, revealable = true)
        if (account != null) {
            Field(number, { number = it.trim() }, account.label)
            Text(account.where, style = PointType.small.copy(color = PointColors.muted))
        }

        if (elsewhere != null) {
            Text(keyOfAnotherService(elsewhere.name, line.name), style = PointType.small.copy(color = PointColors.muted))
            Action(checkAsKeyOf(elsewhere.name)) { onElsewhere(draft, elsewhere.id) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (checking) tr("Проверяю…") else tr("Проверить и включить"), { onCheck(entered()) }, enabled = canCheck)
            if (draft.isNotBlank() && verdict !is KeyVerdict.Works && draft.trim() != saved?.apiKey) {
                Action(tr("Сохранить без проверки")) { onSave(entered()) }
            }
            // Дверь «убрать» видна там, где есть что убирать: прежде на её месте была
            // пустота, и вписанный ключ убрать было нечем (#1080).
            if (saved != null || draft.isNotBlank()) {
                Action(tr("Забыть ключ")) { draft = ""; onForget() }
            }
        }
        verdict?.let { Verdict(it) }
    }
}
