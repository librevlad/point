package com.point

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.ui.FirstScreen

/**
 * Chooses what to render from the current [FlowUiState]. Stateless — the Activity
 * collects the state and forwards intents, so this stays trivial to reason about.
 */
@Composable
fun PointHost(
    state: FlowUiState,
    onBubble: (Bubble) -> Unit,
    onSubmitInput: (String) -> Unit,
    onCancelInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()

            state.frame != null -> FirstScreen(
                obj = state.frame.obj,
                bubbles = state.frame.bubbles,
                onBubble = onBubble,
                message = state.message,
                inputPrompt = state.inputPrompt,
                onSubmitInput = onSubmitInput,
                onCancelInput = onCancelInput,
            )

            else -> Text(
                text = "Поделитесь объектом в Point",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
