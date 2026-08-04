package com.point

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import com.point.core.flow.AppTarget
import com.point.core.flow.UserAiConfig
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.ui.BusyPortal
import com.point.core.ui.FindScreen
import com.point.core.ui.FirstScreen
import com.point.core.ui.Outcome
import com.point.core.ui.OutcomeBanner
import com.point.core.ui.Portal
import com.point.core.ui.PortalColumnWidth
import com.point.core.ui.PortalRow
import com.point.core.ui.ScreenHeader
import com.point.core.ui.SelectionScreen
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleIcon
import com.point.core.ui.livingBackground
import com.point.core.ui.portalCard
import com.point.core.ui.portalStep
import com.point.core.ui.theme.PointTheme
import kotlinx.coroutines.delay

/**
 * Chooses what to render from the current [FlowUiState]. Stateless — the Activity
 * collects the state and forwards intents. Object changes cross-fade/scale in via
 * [AnimatedContent] (keyed by object id), so the flow feels like moving between
 * states rather than snapping.
 */
@Composable
fun PointHost(
    state: FlowUiState,
    onBubble: (Bubble) -> Unit,
    onSubmitInput: (String) -> Unit,
    onCancelInput: () -> Unit,
    /** Отмена идущего действия (#288): передумать можно всегда. */
    onCancelAction: () -> Unit = {},
    /** Тап по объекту без слоя слов — открыть его (#290). */
    onOpenObject: () -> Unit = {},
    onApplyFavorite: (FavoriteChain) -> Unit = {},
    onSaveChain: () -> Unit = {},
    onItem: (PointObject) -> Unit = {},
    onFound: (PointObject) -> Unit = {},
    onJumpTo: (Int) -> Unit = {},
    onBubbleLongPress: (Bubble) -> Unit = {},
    onSaveAiConfig: (UserAiConfig) -> Unit = {},
    /** Пойти за ключом с отказа, который им и чинится (#452) — предложение, а не подмена ответа. */
    onOpenKeySettings: () -> Unit = {},
    /** Проверить ключ живым запросом — только по явному тапу человека (#465). */
    onCheckAiKey: (UserAiConfig) -> Unit = {},
    /** Что лежит в буфере обмена; читается только тапом «Вставить из буфера» (#465). */
    onPasteKey: () -> String? = { null },
    onCloseKeySettings: () -> Unit = {},
    onToggleUsage: (Boolean) -> Unit = {},
    onToggleSound: (Boolean) -> Unit = {},
    onPickPrivacyLevel: (com.point.core.flow.PrivacyLevel) -> Unit = {},
    /** Разрешить/отозвать отправку объектов моделям (#114). */
    onToggleCloud: (Boolean) -> Unit = {},
    /** Открыть страницу, где выдают ключ (#403). */
    onOpenUrl: (String) -> Unit = {},
    onConfirmCloud: () -> Unit = {},
    onDeclineCloud: () -> Unit = {},
    onPickApp: (AppTarget) -> Unit = {},
    onPairPc: (String, Int) -> Unit = { _, _ -> },
    onUnpairPc: () -> Unit = {},
    onClosePcSettings: () -> Unit = {},
    onDismissAppPicker: () -> Unit = {},
    onConfirmPreview: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onSendChat: (String) -> Unit = {},
    onCloseChat: () -> Unit = {},
    /** Остановить идущий вопрос к AI (#453). */
    onCancelChat: () -> Unit = {},
    /** Забрать ответ разговора объектом (#491). */
    onTakeChatAnswer: () -> Unit = {},
    onOpenSelection: () -> Unit = {},
    onSelectRegion: (com.point.core.flow.Box) -> Unit = {},
    onTakeSelection: () -> Unit = {},
    onCloseSelection: () -> Unit = {},
    onFindQuery: (String) -> Unit = {},
    onCloseFind: () -> Unit = {},
    /** Уйти с экрана-сообщения, за которым нет объекта (#114): то же, что «назад» у этой двери. */
    onDismissMessage: () -> Unit = {},
    appIconFor: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    modifier: Modifier = Modifier,
) {
    // The system photo picker for "Заменить фон" (#97): registered here so no Activity needs to
    // change; the picked image URI feeds back through the normal amendment channel.
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSubmitInput(uri.toString()) else onCancelInput()
    }
    LaunchedEffect(state.needsImage) {
        if (state.needsImage != null) {
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .livingBackground() // M5: the canvas itself is alive (MOTION.md №5) — fills edge-to-edge
            .systemBarsPadding(), // targetSdk 35 is edge-to-edge; keep content clear of the system bars
        contentAlignment = Alignment.Center,
    ) {
        val frame = state.frame
        // Заголовок экрана ожидания — он же условие его подъёма (M3: тихая работа его не поднимает).
        val busyTitle = state.busy?.takeIf { showsBusyScreen(state) }
        // Разговор рисуется, пока открыт его экран; сам он живёт дольше (#453).
        val chat = openChatOf(state)
        // Что предложено сделать с отказом (#452): null — предлагать нечего.
        val offer = keyOfferLabel(state.message)
        when {
            // Cloud consent is a gate: it must be answered before anything else renders (#10).
            state.cloudConsent -> ConsentScreen(
                onAllow = onConfirmCloud,
                onDecline = onDeclineCloud,
                destination = state.cloudDestination,
                title = state.cloudTitle,
                confirm = state.cloudConfirm,
            )

            state.appPicker != null -> AppPickerScreen(
                apps = state.appPicker,
                onPick = onPickApp,
                onDismiss = onDismissAppPicker,
                appIconFor = appIconFor,
            )

            // A pre-execution preview is a gate too: confirm what will happen before it runs (#97).
            state.preview != null -> PreviewScreen(
                preview = state.preview,
                onConfirm = onConfirmPreview,
                onCancel = onCancelPreview,
            )

            // Waiting on the photo picker (opened by the LaunchedEffect above).
            state.needsImage != null -> PickingImageScreen(title = state.needsImage)

            state.pcScreen != null -> PairPcScreen(
                state = state.pcScreen,
                onPair = onPairPc,
                onUnpair = onUnpairPc,
                onClose = onClosePcSettings,
            )

            state.keyScreen != null -> KeyScreen(
                config = state.keyScreen,
                note = state.keyScreenNote,
                onSave = onSaveAiConfig,
                onCancel = onCloseKeySettings,
                checking = state.keyChecking,
                verdict = state.keyVerdict,
                onCheck = onCheckAiKey,
                onPasteKey = onPasteKey,
                usageEnabled = state.usageEnabled,
                usageSummary = state.usageSummary,
                onToggleUsage = onToggleUsage,
                soundEnabled = state.soundEnabled,
                onToggleSound = onToggleSound,
                privacyLevel = state.privacyLevel,
                onPickPrivacyLevel = onPickPrivacyLevel,
                cloudEnabled = state.cloudEnabled,
                onToggleCloud = onToggleCloud,
                onOpenUrl = onOpenUrl,
            )

            // M3 (MOTION.md №8): quiet local work keeps the object on screen — it "works"
            // in place; only cloud/slow actions get the full staged busy screen.
            busyTitle != null ->
                BusyScreen(
                    title = busyTitle,
                    stage = state.busyStage,
                    network = state.busyNetwork,
                    // Кнопки нет там, где отменять нечем (#114): она рисуется по одному признаку
                    // с тем, кто держит задачу работы, — см. [showsCancel].
                    onCancel = onCancelAction.takeIf { showsCancel(state) },
                )

            // #259: выделение поверх объекта — страница целиком, рамка пальцем, «Взять».
            state.selection != null -> SelectionScreen(
                image = state.selection.image,
                highlights = state.selection.highlights,
                capturedText = state.selection.text,
                onSelect = onSelectRegion,
                onTake = onTakeSelection,
                onClose = onCloseSelection,
                modifier = Modifier.fillMaxSize(),
            )

            // #279: поиск по документу — та же страница, только рамку рисует запрос человека.
            state.find != null -> FindScreen(
                image = state.find.image,
                highlights = state.find.highlights,
                status = state.find.status,
                onQuery = onFindQuery,
                onClose = onCloseFind,
                modifier = Modifier.fillMaxSize(),
            )

            // #4: the AI chat takes over the screen while open (over the object it discusses).
            chat != null -> AiChatScreen(
                chat = chat,
                onSend = onSendChat,
                onClose = onCloseChat,
                onCancel = onCancelChat,
                onTakeAnswer = onTakeChatAnswer,
                modifier = Modifier.fillMaxSize(),
            )

            frame != null -> Column(Modifier.fillMaxSize()) {
                // The journey so far (#114) — stays put while the object below animates.
                TimelineStrip(path = state.path, onNode = onJumpTo)
                AnimatedContent(
                    targetState = frame,
                    contentKey = { it.obj.id },
                    transitionSpec = {
                        // M3/M3.5 (№9): the generic morph, specialised for the signature
                        // transformations — the change itself tells what happened.
                        val from = initialState.obj.state.kind
                        val to = targetState.obj.state.kind
                        when {
                            // Something became a PDF: the old object folds flat into a sheet,
                            // the page unfolds open.
                            to == ObjectKind.PDF && from != ObjectKind.PDF ->
                                (fadeIn(tween(260, delayMillis = 120)) + expandVertically(
                                    tween(340, delayMillis = 120), expandFrom = Alignment.CenterVertically,
                                )) togetherWith (fadeOut(tween(200)) + scaleOut(tween(240), targetScale = 0.06f))
                            // Recognition: letters slowly come through — a long, calm reveal.
                            from == ObjectKind.IMAGE && to == ObjectKind.TEXT ->
                                fadeIn(tween(520)) togetherWith fadeOut(tween(260))
                            else ->
                                (fadeIn(tween(340)) + scaleIn(
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                                    initialScale = 0.80f,
                                )) togetherWith (fadeOut(tween(150)) + scaleOut(tween(220), targetScale = 1.08f))
                        }
                    },
                    label = "frame",
                    modifier = Modifier.weight(1f),
                ) { current ->
                FirstScreen(
                    obj = current.obj,
                    bubbles = current.bubbles,
                    onBubble = onBubble,
                    message = state.message,
                    messageOutcome = state.messageOutcome,
                    messageOffer = offer,
                    onMessageOffer = onOpenKeySettings,
                    inputPrompt = state.inputPrompt,
                    inputSuggestions = state.inputSuggestions,
                    onSubmitInput = onSubmitInput,
                    onCancelInput = onCancelInput,
                    favorites = state.favorites,
                    onApplyFavorite = onApplyFavorite,
                    canSaveChain = state.canSaveChain,
                    onSaveChain = onSaveChain,
                    items = current.items,
                    itemsTotal = current.itemsTotal,
                    itemsTotalAtLeast = current.itemsTotalAtLeast,
                    onItem = onItem,
                    found = current.found,
                    relations = current.relations,
                    onFound = onFound,
                    textPreview = current.textPreview,
                    latent = current.latent,
                    enriching = current.enriching,
                    discover = current.discover,
                    working = objectWorking(state),
                    // Тихая работа говорит на самом объекте (#288): экран ожидания для неё не
                    // поднимают намеренно (мигал бы на каждом мелком тапе), но и молчать ей
                    // больше нельзя — строка та же, что показал бы экран.
                    workingStage = quietStage(state),
                    previewBitmap = current.preview,
                    pinned = current.pinned,
                    onBubbleLongPress = onBubbleLongPress,
                    appIconFor = appIconFor,
                    // Тап по объекту всегда что-то делает (#290). Картинку — обводим, читали её
                    // или нет (#259): выделение и есть способ указать область, а распознавание —
                    // одно из продолжений, а не пропуск на вход. Всё остальное открывается, как
                    // от кнопки «Открыть»: тишина в ответ на тап по самому крупному элементу
                    // экрана — та же ложь, что заглушка вместо статуса.
                    onHeroTap = when (
                        heroTapOf(
                            current.obj.state.kind,
                            hasWordLayer = current.obj.metadata.containsKey(com.point.core.flow.META_OCR_ATOMS_REF),
                        )
                    ) {
                        HeroTap.SELECT -> onOpenSelection
                        HeroTap.OPEN -> onOpenObject
                    },
                )
                }
            }

            state.message != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                // An ingest/open error before any object exists: show it plainly instead
                // of the empty hint that used to mask it — so it never reads as a silent
                // dead-end (#12).
                //
                // Той же карточкой, что на экране объекта (#358). Здесь этот экран и живёт: когда
                // приём сорвался, объекта нет — значит именно сюда попадает «Не удалось открыть
                // объект», и «языком портала» отказ обязан говорить в первую очередь тут. Красный
                // Material `colorScheme.error` красил всё подряд, включая «Ключ AI сохранён»:
                // удача выглядела сбоем ровно так же, как раньше на экране объекта.
                OutcomeBanner(state.message, state.messageOutcome)
                // Предложение стоит и здесь (#452): отказ «нет ключа» может застать человека и без
                // объекта на экране, и тогда единственный выход отсюда — уйти ни с чем.
                if (offer != null) {
                    Spacer(Modifier.height(10.dp))
                    com.point.core.ui.PortalRow(
                        title = offer,
                        onClick = onOpenKeySettings,
                        modifier = Modifier.widthIn(max = com.point.core.ui.PortalColumnWidth),
                    )
                }
                // Совет есть только у отказа: сказать «поделитесь ещё раз» тому, у кого ничего не
                // ломалось, — выдумать ему проблему.
                shareAgainHint(state.messageOutcome)?.let { hint ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Выход (#114). Раньше здесь не было ни одной кнопки, а «назад» закрывал Point:
                // человек, сохранивший ключ, вылетал из приложения вместо возврата к «Недавнему».
                // Кнопка делает ровно то же, что «назад» у этой двери, — обещание и поведение
                // сходятся, и из состояния-сообщения есть выход обоими путями.
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onDismissMessage) {
                    Text(messageExitLabel(state.messageOutcome))
                }
            }

            else -> Text(
                text = "Поделитесь объектом в Point",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}


/**
 * The working screen — alive, not a frozen wheel (#62). A ticking elapsed counter proves it is
 * running, and for a cloud/AI call the sub-line advances through honest stages so a multi-second
 * wait reads as progress, not a hang. No fake percentages — only what we truly know.
 */
@Composable
private fun BusyScreen(title: String, stage: String?, network: Boolean, onCancel: (() -> Unit)?) {
    var elapsed by remember(title) { mutableIntStateOf(0) }
    LaunchedEffect(title) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    // The portal (redesign slice 1): a glowing "reading" vortex + indicative step checklist on its
    // own near-black stage — replaces the plain wheel (MOTION.md принцип №3, impulses not a spinner).
    // Подпись говорит правду о времени, а не обещает «несколько секунд» (#288): две модели по
    // фото — это минута и больше, и обещание, которое нарушается на 12-й секунде, читается как
    // «зависло». Про отмену подпись напоминает только там, где кнопка есть (#114).
    // Никакого выдуманного чек-листа (#288, консилиум: «замещение реального статуса
    // имитацией»): показываем ТОЛЬКО то, что действие сказало о себе само. Молчит — человек
    // видит идущее время, и это честнее застрявшей бутафории.
    BusyPortal(
        title = title,
        subtitle = waitingSubtitle(elapsed, network, cancelable = onCancel != null),
        steps = listOfNotNull(stage),
        activeStep = 0,
        onCancel = onCancel,
    )
}

/**
 * Что добавить под исходом на экране без объекта: совет повторить шаринг — и только отказу.
 *
 * Экран этот показывают не одному лишь сорванному приёму: сюда же попадает «Ключ AI сохранён»
 * с домашнего экрана. Совет «попробуйте ещё раз» под удачей — выдуманная человеку проблема.
 */
internal fun shareAgainHint(outcome: Outcome): String? =
    if (outcome == Outcome.FAILED) "Попробуйте поделиться объектом в Point ещё раз" else null

/**
 * Что написано на выходе с экрана-сообщения (#114).
 *
 * Слово разное, дверь одна: у сделанного — «Готово», у сорвавшегося — «Понятно». Сказать «Готово»
 * над отказом значит поздравить человека с неудачей.
 */
internal fun messageExitLabel(outcome: Outcome): String =
    if (outcome == Outcome.FAILED) "Понятно" else "Готово"

/**
 * Что честно сказать о времени: сколько уже идёт и почему это нормально.
 *
 * Про отмену подпись напоминает только при [cancelable] — над работой без кнопки строка
 * «можно отменить» отправляла бы человека искать то, чего на экране нет (#114).
 */
internal fun waitingSubtitle(elapsed: Int, network: Boolean, cancelable: Boolean = true): String = when {
    !network -> if (elapsed < 3) "Обрабатываю…" else "Идёт $elapsed с"
    elapsed < 5 -> "Идёт $elapsed с"
    elapsed < 30 -> "Идёт $elapsed с · модель читает документ"
    cancelable -> "Идёт $elapsed с · долгая страница, можно отменить"
    else -> "Идёт $elapsed с · долгая страница"
}

/**
 * Ждём, пока человек выберет фото системным пикером (#97, «Заменить фон»).
 *
 * Ждём порталом и пульсом, а не крутилкой Material (#461, MOTION.md принцип №3): это была последняя
 * `CircularProgressIndicator` в приложении — все остальные ожидания уже говорят так. Вынуто из
 * `when` отдельной функцией, чтобы у экрана было превью: внутри `PointHost` его не показать —
 * там же сидит `LaunchedEffect`, открывающий настоящий пикер.
 */
@Composable
private fun PickingImageScreen(title: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Portal(size = 148.dp)
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = title,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = PortalColumnWidth),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ThinkingDot()
            Text(
                text = "Выберите изображение",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@ComposePreview(name = "Ожидание фото · портал вместо крутилки (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewPickingImage() = PointTheme(darkTheme = true) {
    PickingImageScreen(title = "Выберите фон для замены")
}

/**
 * The inline app-picker (#66): the device's real installed handlers for the object, chosen in Point
 * itself rather than bounced to a system dialog.
 *
 * В языке портала (#461). Чужое приложение здесь — такое же действие над объектом, как «Открыть» на
 * экране объекта, и выглядит оно теперь так же: строка с настоящей иконкой приложения в плите.
 * Раньше это был столбик серых Material-плашек с одним лишь названием — то самое «другое
 * приложение» на экране, и вдобавок иконки, которые Point уже умеет показывать, здесь пропадали.
 */
@Composable
private fun AppPickerScreen(
    apps: List<AppTarget>,
    onPick: (AppTarget) -> Unit,
    onDismiss: () -> Unit,
    appIconFor: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = "Открыть в",
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 15.dp),
        )
        LazyColumn(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                PortalRow(
                    title = app.label,
                    onClick = { onPick(app) },
                    icon = bubbleIcon(APP_ICON),
                    image = remember(app.packageName) { appIconFor(app.packageName) },
                    accent = MaterialTheme.colorScheme.primary,
                    appearIndex = index,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDismiss) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Знак «чужое приложение» из общего словаря — тот же, которым помечены app-строки объекта. */
private const val APP_ICON = "open-in"

/**
 * A pre-execution preview (#97): what the action will do — the parsed contact, event or address —
 * so a terminal step is predictable. Confirm runs it; cancel returns to the bubbles.
 *
 * В языке портала (#461): разобранное лежит на карточке портала, а не на сером `surfaceVariant`, и
 * подтверждение — светящаяся строка основного действия. Здесь это особенно к месту: экран
 * показывают перед необратимым шагом, и «что именно сейчас произойдёт» обязано читаться как ответ
 * Point, а не как системный диалог.
 */
@Composable
private fun PreviewScreen(preview: Preview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = preview.title,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = PortalColumnWidth),
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.widthIn(max = PortalColumnWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .portalCard()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                preview.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            PortalRow(
                title = preview.confirmLabel,
                onClick = onConfirm,
                icon = bubbleIcon("open"),
                primary = true,
                chevron = false,
            )
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onCancel) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@ComposePreview(name = "Открыть в · выбор приложения (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewAppPicker() = PointTheme(darkTheme = true) {
    // Установленные приложения — строками дизайн-системы. Иконок в превью нет (их даёт устройство),
    // и видно, что строка не разваливается без них: знак «чужое приложение» стоит в плите.
    AppPickerScreen(
        apps = listOf(
            AppTarget(label = "Google Диск", packageName = "com.google.android.apps.docs", activity = "a"),
            AppTarget(label = "Telegram", packageName = "org.telegram.messenger", activity = "a"),
            AppTarget(label = "Adobe Acrobat", packageName = "com.adobe.reader", activity = "a"),
        ),
        onPick = {},
        onDismiss = {},
    )
}

@ComposePreview(name = "Предпросмотр · что сейчас произойдёт (#461)", showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun PreviewActionPreview() = PointTheme(darkTheme = true) {
    // Необратимый шаг показан до того, как случился: разобранный контакт на карточке портала,
    // подтверждение — светящаяся строка.
    PreviewScreen(
        preview = Preview(
            title = "Добавить контакт",
            lines = listOf("Олена Ковальчук", "+380 67 123 45 67", "olena@example.com"),
            confirmLabel = "Добавить",
        ),
        onConfirm = {},
        onCancel = {},
    )
}
