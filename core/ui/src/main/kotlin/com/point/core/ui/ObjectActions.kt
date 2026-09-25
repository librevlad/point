package com.point.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.yieldLabel
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import kotlinx.coroutines.delay

/**
 * Разделы действий — каждый со своим заголовком: у раздела значения это само значение
 * ([ValueHead]), у остальных — подпись раздела.
 *
 * [shared] — причина, общая для всех действий экрана: она уже сказана подписью объекта, и у
 * действий остаётся их обещание (#874). Считается по всем разделам сразу, даже когда экран
 * рисует их в двух местах.
 */
@Composable
internal fun ObjectActions(
    sections: List<ActionSection<Bubble>>,
    shared: String?,
    working: Boolean,
    onBubble: (Bubble) -> Unit,
    appIconFor: (String) -> ImageBitmap? = { null },
    head: (ActionSection<Bubble>) -> ValueHead? = { null },
) {
    val dim by animateFloatAsState(if (working) 0.5f else 1f, tween(200), label = "actions-dim")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dim },
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        sections.forEach { section ->
            key(section.key) {
                var expanded by rememberSaveable(section.key, section.items.size) { mutableStateOf(false) }
                val shown = if (expanded) section.items else section.items.take(shownAtOnce(section))
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    val value = head(section)
                    if (value != null) ValueHeader(value) else SectionLabel(section.title)
                    shown.forEachIndexed { index, bubble ->
                        key(bubble.capabilityId.value) {
                            ActionRow(
                                bubble = bubble,
                                shared = shared,
                                index = index,

                                // Подсвечено только продолжение найденного: у вещи без находок
                                // главного действия нет, есть честный выбор.
                                primary = section.leads && index == 0,
                                enabled = !working,
                                appIconFor = appIconFor,
                                onClick = { onBubble(bubble) },
                            )
                        }
                    }
                    val hidden = section.items.size - shown.size
                    if (hidden > 0 || expanded) {
                        TextButton(onClick = { expanded = !expanded }, enabled = !working) {

                            Text(if (expanded) "Свернуть" else "Показать ещё $hidden")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Заголовок раздела значения: как значение называется, само значение и то, что про него
 * нужно знать до действия.
 *
 * Значение берётся одним касанием, как в строке знания (#650/#652/#693), — в буфер идёт
 * [copy], ровно то, что человек хотел бы вставить. Галочка — знак «Point это знает»:
 * значение неизвестно откуда её не получает (#948). Спор прочтений виден здесь же, где
 * человек решает позвонить (P8), — иначе он набрал бы номер, в котором Point сам сомневается.
 */
internal data class ValueHead(
    val name: String,
    val value: String?,
    val copy: String?,
    val known: Boolean,
    val other: String?,
)

@Composable
private fun ValueHeader(head: ValueHead) {
    val clipboard = LocalClipboardManager.current
    var copied by rememberSaveable(head.name, head.copy) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(FACT_COPIED_SHOWN_MS)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .let { base ->
                val copy = head.copy ?: return@let base
                base.clickable {
                    clipboard.setText(AnnotatedString(copy))
                    copied = true
                }
            }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel(head.name)
        head.value?.let { value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (head.known) "✓" else "·",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (head.known) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (copied) "Скопировано" else value,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        head.other?.let { other ->
            Text(
                text = "или: $other",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionRow(
    bubble: Bubble,
    shared: String?,
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
        subtitle = yieldLabel(bubble.yields, bubble.unusableReason.takeIf { it != shared }),
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
