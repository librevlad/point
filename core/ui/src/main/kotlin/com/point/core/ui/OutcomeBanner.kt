package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

enum class Outcome {

    DONE,

    FAILED,

    NONE,
}

@Composable
fun OutcomeBanner(message: String?, outcome: Outcome) {

    var shown by remember { mutableStateOf("") }
    var shownOutcome by remember { mutableStateOf(Outcome.NONE) }
    LaunchedEffect(message, outcome) {
        if (message != null) {
            shown = message
            shownOutcome = outcome
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {

        val accent = when (shownOutcome) {
            Outcome.DONE -> MaterialTheme.colorScheme.primary
            Outcome.FAILED -> OutcomeWarm
            Outcome.NONE -> null
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = 16.dp)

                .widthIn(max = PortalColumnWidth)

                .portalCard(accent = accent)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {

            if (accent != null) {
                key(shown) { OutcomeMark(accent = accent, failed = shownOutcome == Outcome.FAILED) }
            }
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun OutcomeCard(
    title: String,
    outcome: Outcome,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val accent = when (outcome) {
        Outcome.DONE -> MaterialTheme.colorScheme.primary
        Outcome.FAILED -> OutcomeWarm
        Outcome.NONE -> null
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .widthIn(max = PortalColumnWidth)
            .portalCard(accent = accent)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (accent != null) {
            key(title) { OutcomeMark(accent = accent, failed = outcome == Outcome.FAILED) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val OutcomeWarm = Color(0xFFF85938)

@Composable
private fun OutcomeMark(accent: Color, failed: Boolean) {
    val motion = rememberMotionEnabled()
    var appeared by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { appeared = true }
    val ignite by animateFloatAsState(
        targetValue = if (appeared) 0f else 1f,
        animationSpec = tween(520),
        label = "outcome-ignite",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                val s = 1f + 0.22f * ignite
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(PlateBase)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = (0.34f + 0.34f * ignite).coerceAtMost(1f)), Color.Transparent),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(
            imageVector = if (failed) Icons.Filled.Close else Icons.Filled.Check,
            contentDescription = if (failed) "Не получилось" else "Готово",
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}
