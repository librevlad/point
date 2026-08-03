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
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's only entry point (no launcher icon): receives an Android Share and
 * hands the source to [FlowViewModel]. The `content://` Uri is stringified here,
 * at the boundary, so nothing below :app touches Android Uri types.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) handleShare(intent)
        // #7: resume a flow interrupted by process death — only here, so the launcher icon
        // always opens Home. Self-skips when a fresh share just arrived (freshShareArrived).
        viewModel.restoreJourney()

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.onBack()) {
                isEnabled = false
                this@ShareActivity.onBackPressedDispatcher.onBackPressed()
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
                        onCancelAction = viewModel::cancelAction,
                        onOpenObject = viewModel::openTopObject,
                        onApplyFavorite = viewModel::applyFavorite,
                        onSaveChain = viewModel::saveCurrentChain,
                        onItem = viewModel::onItem,
                            onFound = viewModel::onFound,
                        onJumpTo = viewModel::jumpTo,
                        onSendChat = viewModel::sendChatMessage,
                        onCloseChat = viewModel::closeChat,
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
                        onOpenSelection = viewModel::openSelection,
                        onSelectRegion = viewModel::onSelectRegion,
                        onTakeSelection = viewModel::takeSelection,
                        onCloseSelection = viewModel::closeSelection,
                        onFindQuery = viewModel::onFindQuery,
                        onCloseFind = viewModel::closeFind,
                        onCancelPreview = viewModel::cancelPreview,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: a second share landing on the live top activity must start a new
        // flow — without this override the intent is delivered and silently dropped.
        handleShare(intent)
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // mandatory scratch cleanup
        super.onDestroy()
    }

    private fun handleShare(intent: Intent) {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when (
            val incoming = incomingOf(
                action = intent.action,
                type = intent.type,
                data = intent.data?.toString(),
                stream = stream?.toString(),
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                streams = streams?.map { it.toString() }.orEmpty(),
            )
        ) {
            is Incoming.Single -> viewModel.onShared(incoming.uri, incoming.mime)
            is Incoming.Many -> viewModel.onSharedMultiple(incoming.uris)
            is Incoming.Body -> {
                val uri = Uri.fromFile(cacheTextFile(cacheDir, incoming.text))
                viewModel.onShared(uri.toString(), "text/plain")
            }
            null -> Unit
        }
    }
}
