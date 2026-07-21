package com.point.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
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
    onSubmitInput: (String) -> Unit = {},
    onCancelInput: () -> Unit = {},
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

        if (inputPrompt != null) {
            AmendmentInput(
                prompt = inputPrompt,
                onSubmit = onSubmitInput,
                onCancel = onCancelInput,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                bubbles.forEachIndexed { index, bubble ->
                    key(bubble.capabilityId.value) {
                        BubbleItem(bubble = bubble, index = index, onClick = { onBubble(bubble) })
                    }
                }
            }
        }

        MessageBanner(message)
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

@Composable
private fun ObjectHeader(obj: PointObject) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Icon(
                imageVector = kindIcon(obj.state.kind),
                contentDescription = obj.state.kind.name,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxSize(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = obj.metadata["name"] ?: obj.mime,
            style = MaterialTheme.typography.titleMedium,
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
private fun BubbleItem(
    bubble: Bubble,
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

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier
            .width(100.dp)
            .graphicsLayer {
                alpha = progress
                val scale = 0.85f + 0.15f * progress
                scaleX = scale
                scaleY = scale
            },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = bubbleIcon(bubble.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = bubble.title,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AmendmentInput(
    prompt: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
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
