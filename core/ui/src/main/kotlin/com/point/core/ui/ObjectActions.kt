package com.point.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.point.core.flow.yieldLabel
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId

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

            var expanded by rememberSaveable(section.group, section.bubbles.size) { mutableStateOf(false) }
            val shown = if (expanded) section.bubbles else section.bubbles.take(likelyCount(section.bubbles.size))
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel(section.group.label)
                shown.forEachIndexed { index, bubble ->
                    key(bubble.capabilityId.value) {
                        ActionRow(
                            bubble = bubble,
                            index = index,

                            primary = sectionIndex == 0 && index == 0,
                            enabled = !working,
                            pinned = bubble.capabilityId == pinned,
                            appIconFor = appIconFor,
                            onClick = { onBubble(bubble) },
                            onLongClick = { onBubbleLongPress(bubble) },
                        )
                    }
                }
                val hidden = section.bubbles.size - shown.size
                if (hidden > 0 || expanded) {
                    TextButton(onClick = { expanded = !expanded }, enabled = !working) {

                        Text(if (expanded) "Свернуть" else "Показать ещё $hidden")
                    }
                }
            }
        }
    }
}

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
    val isApp = bubble.icon.startsWith("app:")
    val appIcon = if (isApp) remember(bubble.icon) { appIconFor(bubble.icon.removePrefix("app:")) } else null
    val ai = bubble.tier == BubbleTier.AI
    PortalRow(
        title = if (pinned) "★ ${bubble.title}" else bubble.title,
        subtitle = yieldLabel(bubble.yields, bubble.intent),
        subtitleMaxLines = 1,
        onClick = onClick,
        onLongClick = onLongClick,
        icon = bubbleIcon(bubble.icon),
        image = appIcon,
        accent = if (isApp) MaterialTheme.colorScheme.primary else bubbleColor(bubble.icon),
        ring = if (ai) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f) else null,
        primary = primary,
        enabled = enabled,
        appearIndex = index,
    )
}
