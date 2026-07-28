package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import kotlinx.coroutines.delay

/**
 * The object's actions as design-system rows (docs/design-system.png), grouped by intent into the
 * «Variant C» sections the owner picked: Извлечь / Превратить / Отправить. Dense and calm — a list,
 * not the floating bubbles the owner found chaotic ("мало помещается", "хаотичные пузыри"). The
 * ranking within a section is the learning BubblePolicy's; the screen just respects it. While an
 * action runs the whole set dims to point at the busy object.
 */
@Composable
internal fun ObjectActions(
    sections: List<ActionSection>,
    working: Boolean,
    pinned: CapabilityId?,
    onBubble: (Bubble) -> Unit,
    onBubbleLongPress: (Bubble) -> Unit = {},
    appIconFor: (String) -> ImageBitmap? = { null },
) {
    val dim by animateFloatAsState(if (working) 0.5f else 1f, tween(200), label = "actions-dim")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dim },
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = section.group.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                section.bubbles.forEachIndexed { index, bubble ->
                    key(bubble.capabilityId.value) {
                        ActionRow(
                            bubble = bubble,
                            index = index,
                            enabled = !working,
                            pinned = bubble.capabilityId == pinned,
                            appIconFor = appIconFor,
                            onClick = { onBubble(bubble) },
                            onLongClick = { onBubbleLongPress(bubble) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One action as a full-width row (design system): a tinted icon plate, the label, a trailing
 * chevron. An AI action wears a cyan (АКЦЕНТ2/tertiary) ring on its plate — it leaves the device;
 * a device app shows its real icon. Long-press pins the action for this object kind (#66). Rows fade
 * up in a gentle per-section stagger; with reduced motion they appear in place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionRow(
    bubble: Bubble,
    index: Int,
    enabled: Boolean,
    pinned: Boolean,
    appIconFor: (String) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val presence = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(index * 40L)
            presence.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    val isApp = bubble.icon.startsWith("app:")
    val appIcon = if (isApp) remember(bubble.icon) { appIconFor(bubble.icon.removePrefix("app:")) } else null
    val ai = bubble.tier == BubbleTier.AI
    val cardShape = RoundedCornerShape(16.dp)
    val plateShape = RoundedCornerShape(12.dp)

    Surface(
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = presence.value
                translationY = (1f - presence.value) * 10.dp.toPx()
            }
            .clip(cardShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(plateShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (ai) Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, plateShape)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (appIcon != null) {
                    Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(26.dp))
                } else {
                    Icon(
                        imageVector = bubbleIcon(bubble.icon),
                        contentDescription = null,
                        tint = if (isApp) MaterialTheme.colorScheme.onSurface else bubbleColor(bubble.icon),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = if (pinned) "★ ${bubble.title}" else bubble.title, // #66: the user's rule is visible
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
