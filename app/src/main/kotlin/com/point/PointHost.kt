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
import com.point.core.ui.FocusScreen
import com.point.core.ui.ThinkingDot
import com.point.core.ui.bubbleIcon
import com.point.core.ui.livingBackground
import com.point.core.ui.portalCard
import com.point.core.ui.portalStep
import com.point.core.ui.theme.PointTheme
import kotlinx.coroutines.delay

@Composable
fun PointHost(
    state: FlowUiState,
    onBubble: (Bubble) -> Unit,
    onSubmitInput: (String) -> Unit,
    onCancelInput: () -> Unit,

    onCancelAction: () -> Unit = {},

    onOpenObject: () -> Unit = {},
    onItem: (PointObject) -> Unit = {},
    onFound: (PointObject) -> Unit = {},
    onJumpTo: (Int) -> Unit = {},
    onSaveAiKey: (com.point.core.flow.UserAiKey) -> Unit = {},

    onOpenKeySettings: () -> Unit = {},

    onCheckAiKey: (com.point.core.flow.UserAiKey) -> Unit = {},

    onCheckAllAiKeys: () -> Unit = {},

    onPasteKey: () -> String? = { null },

    onForgetAiKey: (String) -> Unit = {},
    onCloseKeySettings: () -> Unit = {},
    onToggleSound: (Boolean) -> Unit = {},
    onPickPrivacyLevel: (com.point.core.flow.PrivacyLevel) -> Unit = {},

    onToggleCloud: (Boolean) -> Unit = {},
    onToggleYolo: (Boolean) -> Unit = {},
    onUnpin: (com.point.core.model.ObjectKind) -> Unit = {},
    onForgetAll: () -> Unit = {},
    tileAdded: Boolean = false,

    onOpenUrl: (String) -> Unit = {},
    onConfirmCloud: () -> Unit = {},
    onDeclineCloud: () -> Unit = {},
    onPickApp: (AppTarget) -> Unit = {},

    onSignIn: () -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    onContinueAfterSignIn: () -> Unit = {},

    onRevokeDevice: (String) -> Unit = {},
    onSignOut: () -> Unit = {},

    onDeleteAccount: () -> Unit = {},

    onOpenDevices: () -> Unit = {},
    onCloseDevices: () -> Unit = {},
    onDismissAppPicker: () -> Unit = {},
    onConfirmPreview: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onSendChat: (String) -> Unit = {},
    onCloseChat: () -> Unit = {},

    onCancelChat: () -> Unit = {},

    onTakeChatAnswer: () -> Unit = {},
    onRunChatOffer: () -> Unit = {},
    onOpenSelection: () -> Unit = {},
    onSelectRegion: (List<com.point.core.flow.FocusPart>) -> Unit = {},
    onClearFocus: () -> Unit = {},
    onTakeSelection: () -> Unit = {},
    onFocusSelection: () -> Unit = {},
    onCloseSelection: () -> Unit = {},
    onFindQuery: (String) -> Unit = {},
    onCloseFind: () -> Unit = {},

    onDismissMessage: () -> Unit = {},

    leaveLabel: String = LEAVE_TO_HOME,
    appIconFor: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    modifier: Modifier = Modifier,
) {

    // Стук компьютера (#817): разрешение спрашивается там, где человек видит свой компьютер.
    val context = androidx.compose.ui.platform.LocalContext.current
    var knockOff by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(knockNotAllowed(context)) }
    val askKnockLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        knockOff = !granted

        // Разрешение получено — адрес доставки уходит на сервер сейчас, а не со следующего
        // запуска (#1118): иначе настройка включена, а просьбы по-прежнему ждут.
        if (granted) {
            (context.applicationContext as? PointApplication)?.tellWhereToKnock()
        }
    }
    val askKnock = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            askKnockLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            knockOff = false
        }
    }

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
            .livingBackground()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val frame = state.frame

        val busyTitle = state.busy?.takeIf { showsBusyScreen(state) }

        val chat = openChatOf(state)

        val offer = keyOfferLabel(state.message)
        when {

            state.signIn != null -> SignInScreen(
                state = state.signIn,
                onSignIn = onSignIn,
                onCancel = onCancelSignIn,
                onOpenAgain = onOpenUrl,
                onContinue = onContinueAfterSignIn,
            )

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

            state.preview != null -> PreviewScreen(
                preview = state.preview,
                onConfirm = onConfirmPreview,
                onCancel = onCancelPreview,
            )

            state.needsImage != null -> PickingImageScreen(title = state.needsImage)

            state.devicesScreen != null -> MyDevicesScreen(
                state = state.devicesScreen,
                onRevoke = onRevokeDevice,
                onSignOut = onSignOut,
                onDeleteAccount = onDeleteAccount,
                onClose = onCloseDevices,
                knockOff = knockOff,
                onAllowKnock = { askKnock() },
            )

            state.keyScreen != null -> KeyScreen(
                screen = state.keyScreen,
                note = state.keyScreenNote,

                errand = state.keyErrand,
                onSave = onSaveAiKey,
                onCancel = onCloseKeySettings,
                checking = state.keyChecking,
                verdict = state.keyVerdict,
                verdictFor = state.keyVerdictFor,
                onCheck = onCheckAiKey,
                onCheckAll = onCheckAllAiKeys,
                onPasteKey = onPasteKey,
                onForgetKey = onForgetAiKey,
                soundEnabled = state.soundEnabled,
                onToggleSound = onToggleSound,
                privacyLevel = state.privacyLevel,
                onPickPrivacyLevel = onPickPrivacyLevel,
                cloudEnabled = state.cloudEnabled,
                onToggleCloud = onToggleCloud,
                yoloEnabled = state.yoloEnabled,
                onToggleYolo = onToggleYolo,
                tileAdded = tileAdded,
                memory = state.memory,
                onForgetAll = onForgetAll,
                version = BuildConfig.VERSION_NAME,
                onOpenUrl = onOpenUrl,
                onOpenDevices = onOpenDevices,
            )

            busyTitle != null ->
                BusyScreen(
                    title = busyTitle,
                    stage = state.busyStage,
                    network = state.busyNetwork,

                    onCancel = onCancelAction.takeIf { showsCancel(state) },
                )

            // Focus — отдельный инструмент, а не форма перед распознаванием (ТЗ владельца
            // 10.08.2026): кисть по умолчанию, ✓ — единственное завершение.
            state.selection != null -> FocusScreen(
                image = state.selection.image,
                layer = state.selection.layer,
                onDone = onSelectRegion,
                onCancel = onCloseSelection,
                modifier = Modifier.fillMaxSize(),
            )

            state.find != null -> FindScreen(
                image = state.find.image,
                highlights = state.find.highlights,
                status = state.find.status,
                onQuery = onFindQuery,
                onClose = onCloseFind,
                modifier = Modifier.fillMaxSize(),
            )

            chat != null -> AiChatScreen(
                chat = chat,
                onSend = onSendChat,
                onClose = onCloseChat,
                onCancel = onCancelChat,
                onTakeAnswer = onTakeChatAnswer,
                onRunOffer = onRunChatOffer,
                modifier = Modifier.fillMaxSize(),
            )

            frame != null -> Column(Modifier.fillMaxSize()) {

                ExitRow(onLeave = onDismissMessage, label = leaveLabel)

                TimelineStrip(path = state.path, onNode = onJumpTo)
                AnimatedContent(
                    targetState = frame,
                    contentKey = { it.obj.id },
                    transitionSpec = {

                        val from = initialState.obj.state.kind
                        val to = targetState.obj.state.kind
                        when {

                            to == ObjectKind.PDF && from != ObjectKind.PDF ->
                                (fadeIn(tween(260, delayMillis = 120)) + expandVertically(
                                    tween(340, delayMillis = 120), expandFrom = Alignment.CenterVertically,
                                )) togetherWith (fadeOut(tween(200)) + scaleOut(tween(240), targetScale = 0.06f))

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
                    items = current.items,
                    itemsTotal = current.itemsTotal,
                    itemsTotalAtLeast = current.itemsTotalAtLeast,
                    onItem = onItem,
                    found = current.found,
                    relations = current.relations,
                    onFound = onFound,
                    textPreview = current.textPreview,
                    textPreviewTruncated = current.textPreviewTruncated,
                    latent = current.latent,
                    enriching = current.enriching,
                    failed = current.failed,
                    working = objectWorking(state),

                    workingStage = quietStage(state),
                    previewBitmap = current.preview,
                    appIconFor = appIconFor,

                    focusPreview = state.focusPreview,
                    focused = current.focus != null,
                    onClearFocus = onClearFocus,

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

                OutcomeBanner(state.message, state.messageOutcome)

                if (offer != null) {
                    Spacer(Modifier.height(10.dp))
                    com.point.core.ui.PortalRow(
                        title = offer,
                        onClick = onOpenKeySettings,
                        modifier = Modifier.widthIn(max = com.point.core.ui.PortalColumnWidth),
                    )
                }

                shareAgainHint(state.messageOutcome)?.let { hint ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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

@Composable
private fun BusyScreen(title: String, stage: String?, network: Boolean, onCancel: (() -> Unit)?) {
    var elapsed by remember(title) { mutableIntStateOf(0) }
    LaunchedEffect(title) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    BusyPortal(
        title = title,
        subtitle = waitingSubtitle(elapsed, network, cancelable = onCancel != null),
        steps = listOfNotNull(stage),
        activeStep = 0,
        onCancel = onCancel,
    )
}

internal fun shareAgainHint(outcome: Outcome): String? =
    if (outcome == Outcome.FAILED) "Попробуйте поделиться объектом в Point ещё раз" else null

internal fun messageExitLabel(outcome: Outcome): String =
    if (outcome == Outcome.FAILED) "Понятно" else "Готово"

// «Долгая страница» — утверждение о самом объекте, а Point на этом экране не знает,
// длинная страница или нет: сеть сама по себе проверена заранее (#690, #691) и не
// заставит ждать без причины, но провайдер может отвечать медленно и на короткой
// странице. «Долгое ожидание» — то же самое честно: про сам факт ожидания, не про
// выдуманную причину (живой прогон 2026-08-09: одна строка текста, «долгая
// страница», 11.5 минуты).
//
// Чем занят Point, говорит стадия под подписью («Отправляю на компьютер», «Смотрю на
// снимок») — она приходит от самого действия. Подпись же знает только, что работа сетевая,
// и выводить из этого «модель читает документ» нельзя (#810): на живом прогоне так
// объяснялась отправка файла на СОБСТВЕННЫЙ компьютер, где никакой модели нет. Это ещё и
// неверно про приватность — Point сам учит смотреть, куда уезжает объект.
internal fun waitingSubtitle(elapsed: Int, network: Boolean, cancelable: Boolean = true): String =
    com.point.core.flow.waitingLine(elapsed, network, cancelable)

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

private const val APP_ICON = "open-in"

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

const val LEAVE_TO_HOME = "← Недавнее"

const val LEAVE_BACK = "← Назад"

@Composable
private fun ExitRow(onLeave: () -> Unit, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onLeave) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Некому сказать про просьбу компьютера: разрешения на уведомления нет.
 *
 * До Android 13 разрешение не спрашивают вовсе — там уведомления разрешены сразу, и строка
 * про них была бы разговором ни о чём.
 */
private fun knockNotAllowed(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
