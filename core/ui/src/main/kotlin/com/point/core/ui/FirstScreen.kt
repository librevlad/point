package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.model.LatentBubble
import com.point.core.model.FavoriteChain
import com.point.core.model.Intent
import com.point.core.model.PointObject

/**
 * The first (and every) screen: a preview of the current object and the bubbles
 * that accept its state. Pure and stateless — driven entirely by its arguments,
 * so it renders fully in `@Preview` with no device or APK. See FirstScreenPreview.
 *
 * When [inputPrompt] is non-null an executor is awaiting free-text input; the
 * bubbles are replaced by a text field. [message] shows a transient result
 * (a Failure reason or a Done confirmation). Bubbles fade/scale in with a stagger
 * — so newly disclosed bubbles (progressive disclosure) visibly appear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstScreen(
    obj: PointObject,
    bubbles: List<Bubble>,
    onBubble: (Bubble) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    inputPrompt: String? = null,
    inputSuggestions: List<String> = emptyList(),
    onSubmitInput: (String) -> Unit = {},
    onCancelInput: () -> Unit = {},
    favorites: List<FavoriteChain> = emptyList(),
    onApplyFavorite: (FavoriteChain) -> Unit = {},
    canSaveChain: Boolean = false,
    onSaveChain: () -> Unit = {},
    items: List<PointObject> = emptyList(),
    onItem: (PointObject) -> Unit = {},
    textPreview: String? = null,
    latent: List<LatentBubble> = emptyList(),
    intents: List<Intent> = emptyList(),
    selectedIntent: Intent? = null,
    onIntent: (Intent) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ObjectHeader(obj)

        Spacer(Modifier.height(40.dp))

        if (items.isNotEmpty() && inputPrompt == null) {
            CollectionItems(items = items, onItem = onItem)
            Spacer(Modifier.height(28.dp))
        }

        if (textPreview != null && inputPrompt == null) {
            TextPreview(text = textPreview, markdown = obj.mime == "text/markdown")
            Spacer(Modifier.height(28.dp))
        }

        if (inputPrompt != null) {
            AmendmentInput(
                prompt = inputPrompt,
                onSubmit = onSubmitInput,
                onCancel = onCancelInput,
                suggestions = inputSuggestions,
            )
        } else {
            val showingIntents = selectedIntent == null
            Text(
                text = selectedIntent?.let { intentTitle(it) } ?: "Что сделать?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (showingIntents) {
                    intents.forEachIndexed { index, intent ->
                        key("intent-${intent.name}") {
                            ActionBubble(
                                icon = intentIcon(intent),
                                title = intentTitle(intent),
                                color = intentColor(intent),
                                index = index,
                                onClick = { onIntent(intent) },
                            )
                        }
                    }
                } else {
                    bubbles.forEachIndexed { index, bubble ->
                        key(bubble.capabilityId.value) {
                            BubbleItem(bubble = bubble, index = index, onClick = { onBubble(bubble) })
                        }
                    }
                }
            }
            if (showingIntents && latent.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                LatentHints(latent)
            }
        }

        if (inputPrompt == null && (favorites.isNotEmpty() || canSaveChain)) {
            Spacer(Modifier.height(20.dp))
            ChainSection(favorites, onApplyFavorite, canSaveChain, onSaveChain)
        }

        MessageBanner(message)
    }
}

/**
 * "Почти доступно" (#97 negotiation): capabilities one signal away, dimmed, each with what it still
 * needs. Informational — it teaches the object's latent powers without crowding the real actions.
 */
@Composable
private fun LatentHints(latent: List<LatentBubble>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Почти доступно",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
        latent.forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = bubbleIcon(hint.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "${hint.title} · ${hint.missing}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainSection(
    favorites: List<FavoriteChain>,
    onApply: (FavoriteChain) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (favorites.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                favorites.forEach { chain ->
                    Surface(
                        onClick = { onApply(chain) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            text = "▸ ${chain.name}",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (canSave) {
            TextButton(onClick = onSave) { Text("★ Сохранить цепочку") }
        }
    }
}

@Composable
private fun MessageBanner(message: String?) {
    // Hold the last message so it stays visible while the banner animates out.
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message != null) shown = message }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = shown,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** For a COLLECTION: a scrollable list of its items. Tapping one drills in — the
 *  normal flow continues on that object (its own bubbles: Открыть/Сохранить/…). */
@Composable
private fun CollectionItems(items: List<PointObject>, onItem: (PointObject) -> Unit) {
    Text(
        text = "Содержимое · ${items.size}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Surface(
                onClick = { onItem(item) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = kindIcon(item.state.kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.metadata["name"] ?: kindLabel(item.state.kind),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** For a TEXT object: its content, readable in-app — scroll + native select/copy. */
@Composable
private fun TextPreview(text: String, markdown: Boolean = false) {
    // AI answers arrive as Markdown — render headings/bold/bullets instead of raw `###`/`**`/`*`.
    val rendered = remember(text, markdown) { if (markdown) markdownToAnnotated(text) else AnnotatedString(text) }
    Text(
        text = "Текст",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = rendered,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ObjectHeader(obj: PointObject) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.secondary,
            shadowElevation = 14.dp,
            modifier = Modifier.size(96.dp),
        ) {
            Icon(
                imageVector = kindIcon(obj.state.kind),
                contentDescription = obj.state.kind.name,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .padding(26.dp)
                    .fillMaxSize(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = obj.metadata["name"] ?: kindLabel(obj.state.kind),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = obj.mime,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BubbleItem(bubble: Bubble, index: Int, onClick: () -> Unit) =
    ActionBubble(
        icon = bubbleIcon(bubble.icon),
        title = bubble.title,
        color = bubbleColor(bubble.icon),
        index = index,
        onClick = onClick,
    )

/** One circular action bubble — an intent or a capability. Fades/scales in with a
 *  stagger so newly disclosed actions visibly appear. */
@Composable
private fun ActionBubble(
    icon: ImageVector,
    title: String,
    color: Color,
    index: Int,
    onClick: () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 260, delayMillis = index * 45),
        label = "bubble-in",
    )

    Column(
        modifier = Modifier
            .width(84.dp)
            .graphicsLayer {
                alpha = progress
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .shadow(10.dp, CircleShape, clip = false, ambientColor = color, spotColor = color)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AmendmentInput(
    prompt: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    suggestions: List<String> = emptyList(),
) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (suggestions.isNotEmpty()) {
            // The 3 most-likely prompts (#86): tap one to run it instead of typing.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onSubmit(suggestion) },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            Button(onClick = { onSubmit(text) }) { Text("Готово") }
        }
    }
}
