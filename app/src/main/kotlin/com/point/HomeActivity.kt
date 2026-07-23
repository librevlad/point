package com.point

import android.content.ClipboardManager
import android.content.Context
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
 * Point's launcher home. Shows recent objects (History); tapping one re-opens it
 * into the flow. While a flow is active it hosts the same [PointHost] as Share.
 */
@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit
                viewModel.hasFlow() -> { viewModel.endFlow(); viewModel.loadRecent() }
                else -> {
                    isEnabled = false
                    this@HomeActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        setContent {
            PointTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    if (state.frame == null && state.busy == null && state.message == null && state.keyScreen == null) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
                        HomeScreen(
                            recent = recent,
                            onOpen = viewModel::openFromHistory,
                            onSettings = viewModel::openKeySettings,
                            onClear = viewModel::clearHistory,
                            clipboard = clipboard,
                            onUseClipboard = ::useClipboard,
                            onDismissClipboard = viewModel::dismissClipboard,
                        )
                    } else {
                        PointHost(
                            state = state,
                            onBubble = viewModel::onBubble,
                            onIntent = viewModel::onIntent,
                            onSubmitInput = viewModel::submitAmendment,
                            onCancelInput = viewModel::cancelInput,
                            onApplyFavorite = viewModel::applyFavorite,
                            onSaveChain = viewModel::saveCurrentChain,
                            onItem = viewModel::onItem,
                            onSaveAiConfig = viewModel::saveAiConfig,
                            onCloseKeySettings = viewModel::closeKeySettings,
                            onToggleUsage = viewModel::setUsageEnabled,
                            onConfirmCloud = viewModel::confirmCloud,
                            onDeclineCloud = viewModel::declineCloud,
                            onPickApp = viewModel::onPickApp,
                            onDismissAppPicker = viewModel::dismissAppPicker,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.hasFlow()) viewModel.loadRecent()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only with window focus (Android 10+), which lands AFTER
        // onResume — reading here is what makes the "act on copied text" offer appear (#72).
        if (hasFocus && !viewModel.hasFlow()) viewModel.offerClipboard(readClipboardText())
    }

    /** The current clipboard text, or null. Only ever called while Point is in the foreground. */
    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun useClipboard(text: String) {
        viewModel.dismissClipboard()
        viewModel.onShared(Uri.fromFile(cacheTextFile(cacheDir, text)).toString(), "text/plain")
    }
}
