package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.LatentBubble
import com.point.core.model.FavoriteChain
import com.point.core.model.PointObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

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
    enriching: List<String> = emptyList(),
    discover: Bubble? = null,
    working: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // The unfolded «Все действия» can outgrow the screen (#114) — the whole
            // screen scrolls; with little content the Center arrangement still centres.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val facts = understoodFacts(obj)
        // M2: the object's centre is the birth/return anchor of every bubble-particle.
        val objectCenter = remember { mutableStateOf(Offset.Unspecified) }
        Box(
            Modifier.onGloballyPositioned { objectCenter.value = it.boundsInRoot().center },
        ) {
            ObjectHeader(obj, thinking = enriching.isNotEmpty() || working, understood = facts.isNotEmpty())
        }

        // «Point понял» (#114): the understanding card — facts land line by line as
        // enrichment delivers them (#64), with still-running work inside the same card.
        UnderstoodSection(facts = facts, enriching = enriching)

        Spacer(Modifier.height(32.dp))

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
            // #114: the likely few, big — everything else folded behind «Все действия».
            // Ranking is the learning BubblePolicy's job; the screen just respects it.
            val likely = bubbles.take(likelyCount(bubbles.size))
            val rest = bubbles.drop(likely.size)
            Text(
                text = if (rest.isEmpty()) "Что сделать?" else "Самые вероятные",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            val fieldAlpha by animateFloatAsState(if (working) 0.45f else 1f, tween(200), label = "field-dim")
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = fieldAlpha },
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                likely.forEachIndexed { index, bubble ->
                    key(bubble.capabilityId.value) {
                        BubbleItem(
                            bubble = bubble, index = index, size = 68.dp,
                            objectCenter = objectCenter.value, enabled = !working,
                            onClick = { onBubble(bubble) },
                        )
                    }
                }
            }
            if (discover != null) {
                Spacer(Modifier.height(14.dp))
                DiscoverHint(discover, onClick = { onBubble(discover) })
            }
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AllActions(rest = rest, objectCenter = objectCenter.value, onBubble = onBubble)
            }
            if (latent.isNotEmpty()) {
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
private fun ObjectHeader(obj: PointObject, thinking: Boolean = false, understood: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // M1 (MOTION.md): the object breathes with its kind's physics; a light ring
        // pulses while enrichment thinks; the shadow warms into an aura once understood.
        AliveSurface(
            kind = obj.state.kind,
            thinking = thinking,
            understood = understood,
            shape = RoundedCornerShape(26.dp),
            size = 96.dp,
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.secondary,
                shadowElevation = 0.dp,
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

/** Discover (#114): ONE folded possibility the user never tried, surfaced as a hint —
 *  «💡 Попробуйте: Создать событие». Tapping runs it like any bubble; once used, the
 *  usage signal retires the hint by itself. How new capabilities get found. */
@Composable
private fun DiscoverHint(bubble: Bubble, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(text = "💡", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Попробуйте: ${bubble.title}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** The folded remainder of the graph (#114): a count that unfolds into the full set,
 *  grouped by [BubbleTier] — the levels themselves teach an action's nature. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllActions(rest: List<Bubble>, objectCenter: Offset, onBubble: (Bubble) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (expanded) "Свернуть" else "Все действия (${rest.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                for ((tier, label) in TIER_GROUPS) {
                    val group = rest.filter { it.tier == tier }
                    if (group.isEmpty()) continue
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        group.forEachIndexed { index, bubble ->
                            key(bubble.capabilityId.value) {
                                BubbleItem(
                                    bubble = bubble, index = index, size = 52.dp,
                                    objectCenter = objectCenter, onClick = { onBubble(bubble) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** #114: how many top-ranked actions are shown big. With ≤2 more than that, folding
 *  is sillier than showing — everything is "likely". Shared with the ViewModel so the
 *  Discover hint knows exactly which actions the user does NOT see. */
const val LIKELY_COUNT = 3

/** The number of actions actually shown big for [total] candidates (see [LIKELY_COUNT]). */
fun likelyCount(total: Int): Int = if (total <= LIKELY_COUNT + 2) total else LIKELY_COUNT

private val TIER_GROUPS = listOf(
    BubbleTier.INSTANT to "Мгновенные",
    BubbleTier.SMART to "Умные",
    BubbleTier.AI to "AI и облако",
)

@Composable
private fun BubbleItem(
    bubble: Bubble,
    index: Int,
    size: Dp = 62.dp,
    objectCenter: Offset = Offset.Unspecified,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = ActionBubble(
    icon = bubbleIcon(bubble.icon),
    title = bubble.title,
    color = bubbleColor(bubble.icon),
    index = index,
    size = size,
    ai = bubble.tier == BubbleTier.AI,
    objectCenter = objectCenter,
    enabled = enabled,
    onClick = onClick,
)

/**
 * A bubble is a particle, not a button (M2, MOTION.md №4): it is **born from the
 * object** (springs out along the object→slot vector, staggered), **drifts** weightlessly
 * in idle with its own period and phase, and on tap is **pulled back into the object**
 * before the action dispatches — the possibility returns to transform it. With reduced
 * motion (or in previews, where the anchor is unknown) it appears in place and fires
 * instantly. An AI bubble wears a quiet tertiary ring — the action leaves the device.
 */
@Composable
private fun ActionBubble(
    icon: ImageVector,
    title: String,
    color: Color,
    index: Int,
    size: Dp = 62.dp,
    ai: Boolean = false,
    objectCenter: Offset = Offset.Unspecified,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val presence = remember { Animatable(0f) }
    var birthVector by remember { mutableStateOf<Offset?>(null) }
    var departing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val drift = remember(index) { driftSpecFor(index) }
    val driftPhase = if (motion) {
        rememberInfiniteTransition(label = "drift").animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(tween(drift.periodMs, easing = LinearEasing)),
            label = "drift-phase",
        ).value
    } else {
        0f
    }

    LaunchedEffect(Unit) {
        if (!motion) {
            presence.snapTo(1f)
            return@LaunchedEffect
        }
        delay(index * 45L)
        presence.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }

    Column(
        // Never narrower than 100dp: the longest one-word Russian labels («Скопировать»)
        // must fit in one line even under the small folded-group bubbles.
        modifier = Modifier
            .width(maxOf(size + 24.dp, 100.dp))
            .onGloballyPositioned { coords ->
                if (birthVector == null && objectCenter.isSpecified) {
                    birthVector = objectCenter - coords.boundsInRoot().center
                }
            }
            .graphicsLayer {
                val p = presence.value
                alpha = p
                val scale = 0.55f + 0.45f * p
                scaleX = scale
                scaleY = scale
                val v = birthVector ?: Offset.Zero
                translationX = v.x * (1f - p)
                translationY = v.y * (1f - p) +
                    sin(driftPhase + drift.phaseRad) * drift.amplitudeDp.dp.toPx() * p
            }
            .clickable(enabled = enabled) {
                when {
                    !motion || birthVector == null -> onClick()
                    !departing -> {
                        // Pulled back into the object, then the action fires (BUBBLE_DEPART_MS).
                        departing = true
                        scope.launch {
                            presence.animateTo(0f, tween(BUBBLE_DEPART_MS, easing = FastOutLinearInEasing))
                            onClick()
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(10.dp, CircleShape, clip = false, ambientColor = color, spotColor = color)
                .then(
                    if (ai) Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                    else Modifier,
                )
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.45f),
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
