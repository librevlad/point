package com.point

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.point.core.model.Bubble
import com.point.core.model.FavoriteChain
import com.point.core.ui.FirstScreen

/**
 * Chooses what to render from the current [FlowUiState]. Stateless — the Activity
 * collects the state and forwards intents. Object changes cross-fade/scale in via
 * [AnimatedContent] (keyed by object id), so the flow feels like moving between
 * states rather than snapping.
 */
@Composable
fun PointHost(
    state: FlowUiState,
    onBubble: (Bubble) -> Unit,
    onSubmitInput: (String) -> Unit,
    onCancelInput: () -> Unit,
    onApplyFavorite: (FavoriteChain) -> Unit = {},
    onSaveChain: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val frame = state.frame
        when {
            state.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "Point выполняет действие",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            frame != null -> AnimatedContent(
                targetState = frame,
                contentKey = { it.obj.id },
                transitionSpec = {
                    (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.94f)) togetherWith
                        fadeOut(tween(140))
                },
                label = "frame",
            ) { current ->
                FirstScreen(
                    obj = current.obj,
                    bubbles = current.bubbles,
                    onBubble = onBubble,
                    message = state.message,
                    inputPrompt = state.inputPrompt,
                    onSubmitInput = onSubmitInput,
                    onCancelInput = onCancelInput,
                    favorites = state.favorites,
                    onApplyFavorite = onApplyFavorite,
                    canSaveChain = state.canSaveChain,
                    onSaveChain = onSaveChain,
                )
            }

            else -> Text(
                text = "Поделитесь объектом в Point",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
