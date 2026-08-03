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

    /**
     * «Принять файл» (#388): экран ожидания отдаёт приехавший файл, и он входит в Point ровно той
     * же дверью, что расшаренный, — обычным объектом. Отмена (RESULT_CANCELED) молчит: человек сам
     * только что закрыл ожидание.
     */
    private val receiveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val path = result.data?.getStringExtra(com.point.source.ReceiveActivity.EXTRA_PATH)
        val mime = result.data?.getStringExtra(com.point.source.ReceiveActivity.EXTRA_MIME)
        if (result.resultCode == RESULT_OK && !path.isNullOrBlank()) {
            viewModel.onShared(Uri.fromFile(java.io.File(path)).toString(), mime ?: "application/octet-stream")
        }
    }

    private fun receiveFile() {
        receiveLauncher.launch(android.content.Intent(this, com.point.source.ReceiveActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()

        // Scanned the PC's pairing QR with a camera → a point-pc:// VIEW intent lands here.
        if (savedInstanceState == null && intent?.action == android.content.Intent.ACTION_VIEW) {
            intent.data?.takeIf { it.scheme == "point-pc" }?.let { viewModel.pairFromPayload(it.toString()) }
        }

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
                    if (state.frame == null && state.busy == null && state.message == null && state.keyScreen == null && state.pcScreen == null) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
                        val crash by viewModel.crashReport.collectAsStateWithLifecycle()
                        val basketCount by viewModel.basketCount.collectAsStateWithLifecycle()
                        val fromPcCount by viewModel.fromPcCount.collectAsStateWithLifecycle()
                        // Re-offer the clipboard each time Home comes back on screen: after Back
                        // out of a restored flow the focus edge has already passed (#111).
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            viewModel.refreshClipboard(::readClipboardText)
                        }
                        HomeScreen(
                            recent = recent,
                            onOpen = viewModel::openFromHistory,
                            onSettings = viewModel::openKeySettings,
                            onPc = viewModel::openPcSettings,
                            onReceive = ::receiveFile,
                            onClear = viewModel::clearHistory,
                            clipboard = clipboard,
                            onUseClipboard = ::useClipboard,
                            onDismissClipboard = viewModel::dismissClipboard,
                            crashReport = crash,
                            onSendCrash = ::shareCrashReport,
                            onDismissCrash = viewModel::dismissCrashReport,
                            basketCount = basketCount,
                            onOpenBasket = viewModel::openBasket,
                            onClearBasket = viewModel::clearBasket,
                            fromPcCount = fromPcCount,
                            onPullFromPc = viewModel::pullFromPc,
                            onHideFromPc = viewModel::hideFromPc,
                        )
                    } else {
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
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.hasFlow()) viewModel.loadRecent()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only with window focus (Android 10+), which lands AFTER
        // onResume — reading here is what makes the "act on copied text" offer appear (#72).
        if (hasFocus && !viewModel.hasFlow()) {
            val text = readClipboardText()
            viewModel.offerClipboard(text)
            // #111: on some devices the clipboard is not yet served at the focus edge —
            // one delayed retry closes that race without ever polling in background.
            if (text.isNullOrBlank()) {
                window.decorView.postDelayed({
                    if (hasWindowFocus() && !viewModel.hasFlow()) {
                        viewModel.offerClipboard(readClipboardText())
                    }
                }, CLIPBOARD_RETRY_MS)
            }
        }
    }

    /** The current clipboard text, or null. Only ever called while Point is in the foreground. */
    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    /** #11: the report leaves the device only through the user's own share choice. */
    private fun shareCrashReport(report: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Point - отчёт о падении")
            .putExtra(android.content.Intent.EXTRA_TEXT, report)
        startActivity(android.content.Intent.createChooser(send, "Отправить отчёт"))
        viewModel.dismissCrashReport()
    }

    private fun useClipboard(text: String) {
        viewModel.dismissClipboard()
        viewModel.onShared(Uri.fromFile(cacheTextFile(cacheDir, text)).toString(), "text/plain")
    }
}

private const val CLIPBOARD_RETRY_MS = 300L
