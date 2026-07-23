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
import com.point.core.model.Intent
import com.point.core.model.PointObject
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
    onItem: (PointObject) -> Unit = {},
    onIntent: (Intent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val frame = state.frame
        when {
            state.busy != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = state.busy, // WHAT is running — not a faceless wheel
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
                    bubbles = state.intentBubbles,
                    onBubble = onBubble,
                    message = state.message,
                    inputPrompt = state.inputPrompt,
                    onSubmitInput = onSubmitInput,
                    onCancelInput = onCancelInput,
                    favorites = state.favorites,
                    onApplyFavorite = onApplyFavorite,
                    canSaveChain = state.canSaveChain,
                    onSaveChain = onSaveChain,
                    items = current.items,
                    onItem = onItem,
                    textPreview = current.textPreview,
                    intents = state.intents,
                    selectedIntent = state.selectedIntent,
                    onIntent = onIntent,
                )
            }

            state.message != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                // An ingest/open error before any object exists: show it plainly instead
                // of the empty hint that used to mask it — so it never reads as a silent
                // dead-end (#12).
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Попробуйте поделиться объектом в Point ещё раз",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
