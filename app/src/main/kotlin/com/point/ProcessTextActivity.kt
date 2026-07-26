package com.point

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The "right-click for text" entry point: Point registers for ACTION_PROCESS_TEXT, so selecting text
 * in ANY app shows "Point" in the selection toolbar. The selected text enters the flow through the
 * same `onShared(fileUri, "text/plain")` path as a shared object — no permissions, API 23+.
 */
@AndroidEntryPoint
class ProcessTextActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) handleProcessText(intent)

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.onBack()) {
                isEnabled = false
                this@ProcessTextActivity.onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            PointTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    PointHost(
                        state = state,
                        onBubble = viewModel::onBubble,
                        appIconFor = viewModel::appIcon,
                        onPairPc = viewModel::pairPc,
                        onUnpairPc = viewModel::unpairPc,
                        onClosePcSettings = viewModel::closePcSettings,
                        onSubmitInput = viewModel::submitAmendment,
                        onCancelInput = viewModel::cancelInput,
                        onApplyFavorite = viewModel::applyFavorite,
                        onSaveChain = viewModel::saveCurrentChain,
                        onItem = viewModel::onItem,
                        onJumpTo = viewModel::jumpTo,
                            onBubbleLongPress = viewModel::togglePin,
                        onSaveAiConfig = viewModel::saveAiConfig,
                        onCloseKeySettings = viewModel::closeKeySettings,
                        onToggleUsage = viewModel::setUsageEnabled,
                            onToggleSound = viewModel::setSoundEnabled,
                        onConfirmCloud = viewModel::confirmCloud,
                        onDeclineCloud = viewModel::declineCloud,
                        onPickApp = viewModel::onPickApp,
                        onDismissAppPicker = viewModel::dismissAppPicker,
                        onConfirmPreview = viewModel::confirmPreview,
                        onCancelPreview = viewModel::cancelPreview,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // mandatory scratch cleanup
        super.onDestroy()
    }

    private fun handleProcessText(intent: Intent) {
        // EXTRA_PROCESS_TEXT is the editable selection; the READONLY variant is the fallback.
        val text = (
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            )?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }
        val uri = Uri.fromFile(cacheTextFile(cacheDir, text))
        viewModel.onShared(uri.toString(), "text/plain")
    }
}
