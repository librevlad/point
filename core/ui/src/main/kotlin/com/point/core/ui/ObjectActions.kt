package com.point.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.point.core.flow.yieldLabel
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId

/**
 * The object's actions as design-system rows (docs/design-system.png), grouped by intent into the
 * «Variant C» sections the owner picked: Извлечь / Превратить / Отправить. Dense and calm — a list,
 * not the floating bubbles the owner found chaotic. The single top-ranked action is the bold focal
 * «Основное действие»; the rest are quiet, with depth and colour-lit icons so the list reads
 * premium, not flat. Ranking within a section is the learning BubblePolicy's; the screen just
 * respects it. While an action runs the whole set dims to point at the busy object.
 *
 * Из чего сделана строка — [PortalRow] и [PortalPlate] (дизайн-система): те же значения, что были
 * здесь, только теперь до них дотягиваются и остальные экраны.
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
                SectionLabel(section.group.label)
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
 * One action as a full-width [PortalRow]. An AI action wears a cyan (АКЦЕНТ2/tertiary) ring — it
 * leaves the device; a device app shows its real icon. Long-press pins the action for this object
 * kind (#66).
 *
 * Под названием — **что вернётся** (#491): «вернёт текст», «вернёт таблицу», «ничего не вернёт —
 * отправит». Формула продукта кончается объектом, и до этого среза последнее её звено человеку
 * никто не называл: он узнавал, что получится, только выполнив действие. Слова приходят от самой
 * способности (`Bubble.yields`), экран их не сочиняет.
 */
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
        title = if (pinned) "★ ${bubble.title}" else bubble.title, // #66: the user's rule is visible
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
