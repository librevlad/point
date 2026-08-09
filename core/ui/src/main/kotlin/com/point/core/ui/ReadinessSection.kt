package com.point.core.ui

import kotlinx.coroutines.delay
import com.point.core.flow.copyableValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.flow.ActionReadiness
import com.point.core.flow.Readiness
import com.point.core.flow.actionReadiness
import com.point.core.flow.maskedForScreen
import com.point.core.flow.readingModeOf
import com.point.core.flow.readingModeLabel
import com.point.core.flow.runner
import com.point.core.flow.shownField
import com.point.core.model.Bubble

@Composable
internal fun ReadinessSection(
    metadata: Map<String, String>,
    bubbles: List<Bubble> = emptyList(),
    enabled: Boolean = true,
    onBubble: (Bubble) -> Unit = {},
) {
    val rows = remember(metadata) { actionReadiness(metadata) }
    if (rows.isEmpty()) return

    val understood = metadata["op"] == "understand"
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(top = 12.dp)
            .widthIn(max = 340.dp)
            .animateContentSize(tween(220)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            rows.forEach { row ->
                key(row.schema.id) {
                    ReadinessRow(
                        row = row,
                        understood = understood,
                        metadata = metadata,
                        runner = if (enabled) row.runner(bubbles) else null,
                        onRun = onBubble,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    row: ActionReadiness,
    understood: Boolean,
    metadata: Map<String, String>,
    runner: Bubble?,
    onRun: (Bubble) -> Unit,
) {
    val ready = row.readiness is Readiness.Ready
    val present = when (val r = row.readiness) {
        is Readiness.Ready -> r.present
        is Readiness.Missing -> r.present
    }
    val missing = (row.readiness as? Readiness.Missing)?.missing.orEmpty()
    val disputed = present.filter { it.alternatives.isNotEmpty() }
    val hinted = present.mapNotNull { field -> field.hint?.let { field to it } }

    var expanded by rememberSaveable(row.schema.id) { mutableStateOf(false) }

    val copyable = remember(row) { copyableValue(row.readiness) }
    val clipboard = LocalClipboardManager.current
    var copied by remember(row.schema.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_SHOWN_MS)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()

            .let {
                when {
                    runner != null -> it.clickable { onRun(runner) }
                    missing.isNotEmpty() -> it.clickable { expanded = !expanded }
                    copyable != null -> it.clickable {
                        clipboard.setText(AnnotatedString(copyable))
                        copied = true
                    }
                    else -> it
                }
            }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (ready) "✓" else "•",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = row.schema.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val keyField = row.shownField()
            if (keyField != null) {
                Spacer(Modifier.width(6.dp))
                val origin = readingModeLabel(readingModeOf(metadata))?.let { " · $it" }.orEmpty()
                val doubt = if (keyField.assumption) " · возможно" else ""

                Text(

                    text = if (copied) {
                        "Скопировано"
                    } else {
                        maskedForScreen(keyField.spec.key, keyField.value) + doubt + origin
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (copied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (runner != null) {
                Spacer(Modifier.weight(1f))
            }

            if (runner != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else if (copyable != null) {

                // Дверь этой строки — тихая копия ключевого значения: без значка
                // тап выглядел кнопкой в никуда (живой прогон 2026-08-09).
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Скопировать",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        hinted.forEach { (field, hint) ->
            Text(
                text = "${field.spec.label} — со страницы «${field.value}», обычно передают «$hint»",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }

        disputed.forEach { field ->
            Text(
                text = "${field.spec.label} — или: " +
                    field.alternatives.joinToString(", ") { maskedForScreen(field.spec.key, it) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }

        // «Ещё» — другие объекты того же вида, не спор прочтений (S6).
        present.filter { it.extras.isNotEmpty() }.forEach { field ->
            Text(
                text = "${field.spec.label} — ещё: " +
                    field.extras.joinToString(", ") { maskedForScreen(field.spec.key, it) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        if (!ready) {
            Text(
                text = "не хватает только: " + missing.joinToString(", ") { it.label },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        if (expanded) {
            missing.forEach { spec ->

                val blockedReadings = metadata[spec.key + com.point.core.flow.META_BLOCKED_SUFFIX]
                    ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
                Text(

                    text = when {
                        blockedReadings.isNotEmpty() ->
                            "${spec.label} — прочиталось «${blockedReadings.first()}», но контрольная цифра не сошлась"
                        understood -> "${spec.label} — в прочитанном не нашлось"
                        else -> "${spec.label} — офлайн не нашлось; «Понять» может найти"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                )
            }
        }
    }
}

private const val COPIED_SHOWN_MS = 1_600L
