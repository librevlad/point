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
    onBubble: (Bubble) -> Unit,
    appIconFor: (String) -> ImageBitmap? = { null },

    /**
     * С объектом нечего делать: причина сказана подписью объекта (#684/#685), и обещать над
     * ней результат нельзя (#994). Закрытый режим и отсутствие сети — другое дело: там объект
     * годен, действие живо, и его обещание остаётся при нём.
     */
    unfit: Boolean = false,
) {
    val dim by animateFloatAsState(if (working) 0.5f else 1f, tween(200), label = "actions-dim")

    // Причина, общая для всех действий, уже сказана подписью объекта — у действий остаётся
    // их обещание (#874). Своя причина у отдельного действия никуда не девается.
    val shared = com.point.core.flow.sharedUnusableReason(
        sections.flatMap { it.bubbles }.map { it.unusableReason },
    )
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
                            shared = shared,
                            unfit = unfit,
                            index = index,

                            primary = sectionIndex == 0 && index == 0,
                            enabled = !working,
                            appIconFor = appIconFor,
                            onClick = { onBubble(bubble) },
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
    shared: String?,
    unfit: Boolean,
    index: Int,
    primary: Boolean,
    enabled: Boolean,
    appIconFor: (String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val isApp = bubble.icon.startsWith("app:")
    val appIcon = if (isApp) remember(bubble.icon) { appIconFor(bubble.icon.removePrefix("app:")) } else null
    val ai = bubble.tier == BubbleTier.AI
    PortalRow(
        title = bubble.title,
        subtitle = yieldLabel(
            bubble.yields,
            bubble.unusableReason.takeIf { it != shared },

            // Про негодный объект обещаний не дают (#994): причина сказана подписью объекта,
            // а на её месте стояло «найдёт суть, суммы, даты и контакты».
            promiseHolds = !unfit,
        ),
        subtitleMaxLines = 1,
        onClick = onClick,
        icon = bubbleIcon(bubble.icon),
        image = appIcon,
        accent = if (isApp) MaterialTheme.colorScheme.primary else bubbleColor(bubble.icon),
        ring = if (ai) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f) else null,
        primary = primary,
        enabled = enabled,
        appearIndex = index,
    )
}
