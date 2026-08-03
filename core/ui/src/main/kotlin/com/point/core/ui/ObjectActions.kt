package com.point.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

// Premium dark-neon tokens for the action list (design system, docs/design-system.png). The list must
// not read "cheap": a top-lit surface with real depth, colour-lit icon plates, and one bold focal row.
//
// Не private: карточка исхода (OutcomeBanner) сделана из тех же токенов — «в языке портала» не может
// держаться на второй копии тех же констант, она разъедется молча при первой же правке одной из них.
internal val RowTop = Color(0xFF1A1D25)      // top of the row's subtle top-lit gradient
internal val RowBottom = Color(0xFF121419)   // bottom — a hair below surface, gives the row body depth
internal val PlateBase = Color(0xFF1F222B)   // icon plate base under its colour glow
private val PrimaryStart = Color(0xFF7B5CFF) // АКЦЕНТ1 — the hero gradient start (violet)
private val PrimaryEnd = Color(0xFF4E7BFF)   // toward blue (cyan is reserved for the AI ring)
internal val TopHighlight = Color(0x12FFFFFF) // 7% white top-edge highlight — the "crafted glass" tell

/**
 * The object's actions as design-system rows (docs/design-system.png), grouped by intent into the
 * «Variant C» sections the owner picked: Извлечь / Превратить / Отправить. Dense and calm — a list,
 * not the floating bubbles the owner found chaotic. The single top-ranked action ([primaryId]) is the
 * bold focal "Основное действие"; the rest are quiet, with depth and colour-lit icons so the list
 * reads premium, not flat. Ranking within a section is the learning BubblePolicy's; the screen just
 * respects it. While an action runs the whole set dims to point at the busy object.
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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(
                    text = section.group.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                section.bubbles.forEachIndexed { index, bubble ->
                    key(bubble.capabilityId.value) {
                        ActionRow(
                            bubble = bubble,
                            index = index,
                            // The first action of the first section is the bold focal «Основное действие».
                            primary = sectionIndex == 0 && index == 0,
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
 * One action as a full-width row (design system). The focal [primary] row is a violet→blue gradient
 * with a coloured glow ("Основное действие"); the rest are top-lit dark cards with a colour-lit icon
 * plate. An AI action wears a cyan (АКЦЕНТ2/tertiary) ring — it leaves the device; a device app shows
 * its real icon. Long-press pins the action for this object kind (#66). Rows fade up in a gentle
 * per-section stagger; with reduced motion they appear in place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionRow(
    bubble: Bubble,
    index: Int,
    primary: Boolean,
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
    val accent = if (isApp) MaterialTheme.colorScheme.primary else bubbleColor(bubble.icon)
    val cardShape = RoundedCornerShape(18.dp)
    val plateShape = RoundedCornerShape(14.dp)

    val base = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = presence.value
            translationY = (1f - presence.value) * 10.dp.toPx()
        }

    val surfaceModifier =
        if (primary) {
            base
                .shadow(20.dp, cardShape, ambientColor = PrimaryStart, spotColor = PrimaryStart)
                .clip(cardShape)
                .background(Brush.horizontalGradient(listOf(PrimaryStart, PrimaryEnd)))
        } else {
            base
                .shadow(6.dp, cardShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(cardShape)
                .background(Brush.verticalGradient(listOf(RowTop, RowBottom)))
                .border(1.dp, Brush.verticalGradient(listOf(TopHighlight, Color.Transparent)), cardShape)
        }

    val labelColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface
    val chevronColor = if (primary) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = surfaceModifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (primary) 16.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconPlate(bubble = bubble, accent = accent, ai = ai, primary = primary, appIcon = appIcon, shape = plateShape)
            Text(
                text = if (pinned) "★ ${bubble.title}" else bubble.title, // #66: the user's rule is visible
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = chevronColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The leading icon plate: a colour-lit tile (radial glow of the action's colour) with the icon in that
 *  colour; the primary row gets a white-glass plate; an AI action a cyan ring; a device app its real icon. */
@Composable
private fun IconPlate(
    bubble: Bubble,
    accent: Color,
    ai: Boolean,
    primary: Boolean,
    appIcon: ImageBitmap?,
    shape: RoundedCornerShape,
) {
    val plate = Modifier
        .size(46.dp)
        .clip(shape)
        .then(
            if (primary) {
                Modifier
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.30f), shape)
            } else {
                Modifier
                    .background(PlateBase)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), Color.Transparent)))
                    .border(
                        1.dp,
                        if (ai) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f) else accent.copy(alpha = 0.30f),
                        shape,
                    )
            },
        )
    Box(modifier = plate, contentAlignment = Alignment.Center) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(26.dp))
        } else {
            Icon(
                imageVector = bubbleIcon(bubble.icon),
                contentDescription = null,
                tint = if (primary) Color.White else accent,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}
