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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.point.core.flow.AppTarget
import com.point.core.flow.UserAiConfig
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.ui.BusyPortal
import com.point.core.ui.FirstScreen
import com.point.core.ui.SelectionScreen
import com.point.core.ui.livingBackground
import com.point.core.ui.portalStep
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
    onApplyFavorite: (FavoriteChain) -> Unit = {},
    onSaveChain: () -> Unit = {},
    onItem: (PointObject) -> Unit = {},
    onFound: (PointObject) -> Unit = {},
    onJumpTo: (Int) -> Unit = {},
    onBubbleLongPress: (Bubble) -> Unit = {},
    onSaveAiConfig: (UserAiConfig) -> Unit = {},
    onCloseKeySettings: () -> Unit = {},
    onToggleUsage: (Boolean) -> Unit = {},
    onToggleSound: (Boolean) -> Unit = {},
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
    onOpenSelection: () -> Unit = {},
    onSelectRegion: (com.point.core.flow.Box) -> Unit = {},
    onTakeSelection: () -> Unit = {},
    onCloseSelection: () -> Unit = {},
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
        when {
            // Cloud consent is a gate: it must be answered before anything else renders (#10).
            state.cloudConsent -> ConsentScreen(
                onAllow = onConfirmCloud,
                onDecline = onDeclineCloud,
            )

            state.appPicker != null -> AppPickerScreen(
                apps = state.appPicker,
                onPick = onPickApp,
                onDismiss = onDismissAppPicker,
            )

            // A pre-execution preview is a gate too: confirm what will happen before it runs (#97).
            state.preview != null -> PreviewScreen(
                preview = state.preview,
                onConfirm = onConfirmPreview,
                onCancel = onCancelPreview,
            )

            // Waiting on the photo picker (opened by the LaunchedEffect above).
            state.needsImage != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = state.needsImage,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
            }

            state.pcScreen != null -> PairPcScreen(
                state = state.pcScreen,
                onPair = onPairPc,
                onUnpair = onUnpairPc,
                onClose = onClosePcSettings,
            )

            state.keyScreen != null -> KeyScreen(
                config = state.keyScreen,
                onSave = onSaveAiConfig,
                onCancel = onCloseKeySettings,
                usageEnabled = state.usageEnabled,
                usageSummary = state.usageSummary,
                onToggleUsage = onToggleUsage,
                soundEnabled = state.soundEnabled,
                onToggleSound = onToggleSound,
            )

            // M3 (MOTION.md №8): quiet local work keeps the object on screen — it "works"
            // in place; only cloud/slow actions get the full staged busy screen.
            state.busy != null && !state.busyQuiet ->
                BusyScreen(title = state.busy, network = state.busyNetwork)

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

            // #4: the AI chat takes over the screen while open (over the object it discusses).
            state.chat != null -> AiChatScreen(
                chat = state.chat,
                onSend = onSendChat,
                onClose = onCloseChat,
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
                    inputPrompt = state.inputPrompt,
                    inputSuggestions = state.inputSuggestions,
                    onSubmitInput = onSubmitInput,
                    onCancelInput = onCancelInput,
                    favorites = state.favorites,
                    onApplyFavorite = onApplyFavorite,
                    canSaveChain = state.canSaveChain,
                    onSaveChain = onSaveChain,
                    items = current.items,
                    onItem = onItem,
                    found = current.found,
                    relations = current.relations,
                    onFound = onFound,
                    textPreview = current.textPreview,
                    latent = current.latent,
                    enriching = current.enriching,
                    discover = current.discover,
                    working = state.busy != null && state.busyQuiet,
                    previewBitmap = current.preview,
                    pinned = current.pinned,
                    onBubbleLongPress = onBubbleLongPress,
                    appIconFor = appIconFor,
                    // #259: герой открывает выделение только когда слой слов уже прочитан —
                    // без атомов рамке не к чему прилипать, и тап честно не предлагается.
                    onHeroTap = if (current.obj.metadata.containsKey(com.point.core.flow.META_OCR_ATOMS_REF)) {
                        onOpenSelection
                    } else {
                        null
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
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Попробуйте поделиться объектом в Point ещё раз",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

private val NETWORK_STAGES = listOf("Отправляю в облако…", "Модель обрабатывает запрос…", "Собираю ответ…")
private val LOCAL_STAGES = listOf("Обрабатываю…")

/**
 * The working screen — alive, not a frozen wheel (#62). A ticking elapsed counter proves it is
 * running, and for a cloud/AI call the sub-line advances through honest stages so a multi-second
 * wait reads as progress, not a hang. No fake percentages — only what we truly know.
 */
@Composable
private fun BusyScreen(title: String, network: Boolean) {
    var elapsed by remember(title) { mutableIntStateOf(0) }
    LaunchedEffect(title) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    val stages = if (network) NETWORK_STAGES else LOCAL_STAGES
    // The portal (redesign slice 1): a glowing "reading" vortex + indicative step checklist on its
    // own near-black stage — replaces the plain wheel (MOTION.md принцип №3, impulses not a spinner).
    BusyPortal(
        title = title,
        subtitle = "Это займёт несколько секунд",
        steps = stages,
        activeStep = portalStep(elapsed, stages.size),
    )
}

/**
 * The inline app-picker (#66): the device's real installed handlers for the object, chosen in Point
 * itself rather than bounced to a system dialog.
 */
@Composable
private fun AppPickerScreen(apps: List<AppTarget>, onPick: (AppTarget) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Открыть в",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                Surface(
                    onClick = { onPick(app) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onDismiss) {
            Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A pre-execution preview (#97): what the action will do — the parsed contact, event or address —
 * so a terminal step is predictable. Confirm runs it; cancel returns to the bubbles.
 */
@Composable
private fun PreviewScreen(preview: Preview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = preview.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
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
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) {
                Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onConfirm) { Text(preview.confirmLabel) }
        }
    }
}
