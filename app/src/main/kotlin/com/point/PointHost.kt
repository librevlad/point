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
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.ui.FirstScreen
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
    onJumpTo: (Int) -> Unit = {},
    onSaveAiConfig: (UserAiConfig) -> Unit = {},
    onCloseKeySettings: () -> Unit = {},
    onToggleUsage: (Boolean) -> Unit = {},
    onConfirmCloud: () -> Unit = {},
    onDeclineCloud: () -> Unit = {},
    onPickApp: (AppTarget) -> Unit = {},
    onDismissAppPicker: () -> Unit = {},
    onConfirmPreview: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

            state.keyScreen != null -> KeyScreen(
                config = state.keyScreen,
                onSave = onSaveAiConfig,
                onCancel = onCloseKeySettings,
                usageEnabled = state.usageEnabled,
                usageSummary = state.usageSummary,
                onToggleUsage = onToggleUsage,
            )

            // M3 (MOTION.md №8): quiet local work keeps the object on screen — it "works"
            // in place; only cloud/slow actions get the full staged busy screen.
            state.busy != null && !state.busyQuiet ->
                BusyScreen(title = state.busy, network = state.busyNetwork)

            frame != null -> Column(Modifier.fillMaxSize()) {
                // The journey so far (#114) — stays put while the object below animates.
                TimelineStrip(path = state.path, onNode = onJumpTo)
                AnimatedContent(
                    targetState = frame,
                    contentKey = { it.obj.id },
                    transitionSpec = {
                        // M3 transformation morph (№9): the new state grows out of the old
                        // one with a soft spring — 300–500 мс of visible becoming.
                        (fadeIn(tween(340)) + scaleIn(
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                            initialScale = 0.80f,
                        )) togetherWith (fadeOut(tween(150)) + scaleOut(tween(220), targetScale = 1.08f))
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
                    textPreview = current.textPreview,
                    latent = current.latent,
                    enriching = current.enriching,
                    discover = current.discover,
                    working = state.busy != null && state.busyQuiet,
                    previewBitmap = current.preview,
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
    val stage = stages[minOf(elapsed / 4, stages.lastIndex)]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = title, // WHAT is running — not a faceless wheel
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (elapsed >= 3) "$stage · $elapsed с" else stage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
