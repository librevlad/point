package com.point

import android.content.ClipboardManager
import android.content.Context
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

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    @javax.inject.Inject lateinit var sources: Set<@JvmSuppressWildcards com.point.source.ObjectSource>

    private val sourceLabels: List<String> by lazy {
        sources.filter { it.isAvailable(this) }.map { it.label }.sorted()
    }

    private fun newObject() {
        startActivity(android.content.Intent(this, com.point.source.SourcePickerActivity::class.java))
    }

    private fun example() {
        viewModel.openExample(exampleObject(packageName))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadRecent()

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit

                viewModel.dismissMessage() -> viewModel.loadRecent()
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
                    if (state.frame == null && state.busy == null && state.message == null &&
                        state.keyScreen == null && state.devicesScreen == null && state.signIn == null
                    ) {
                        val recent by viewModel.recent.collectAsStateWithLifecycle()
                        val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
                        val crash by viewModel.crashReport.collectAsStateWithLifecycle()
                        val fromPcCount by viewModel.fromPcCount.collectAsStateWithLifecycle()

                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            viewModel.refreshClipboard(::readClipboardText)
                        }
                        HomeScreen(
                            recent = recent,
                            onOpen = viewModel::openFromHistory,

                            onSettings = viewModel::openKeySettings,
                            onNewObject = ::newObject,
                            onExample = ::example,
                            sourceLabels = sourceLabels,
                            onClear = viewModel::clearHistory,
                            clipboard = clipboard,
                            onUseClipboard = ::useClipboard,
                            onDismissClipboard = viewModel::dismissClipboard,
                            crashReport = crash,
                            onSendCrash = ::shareCrashReport,
                            onDismissCrash = viewModel::dismissCrashReport,
                            fromPcCount = fromPcCount,
                            onPullFromPc = viewModel::pullFromPc,
                            onHideFromPc = viewModel::hideFromPc,

                            aiKeySet = state.aiKeySet,
                        )
                    } else {
                        PointFlow(
                            state = state,
                            viewModel = viewModel,

                            onLeave = { onBackPressedDispatcher.onBackPressed() },
                            leaveLabel = LEAVE_TO_HOME,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.hasFlow()) viewModel.loadRecent()

        viewModel.resumeSignIn()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && !viewModel.hasFlow()) {
            val text = readClipboardText()
            viewModel.offerClipboard(text)

            if (text.isNullOrBlank()) {
                window.decorView.postDelayed({
                    if (hasWindowFocus() && !viewModel.hasFlow()) {
                        viewModel.offerClipboard(readClipboardText())
                    }
                }, CLIPBOARD_RETRY_MS)
            }
        }
    }

    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

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

        viewModel.onSharedText(text)
    }
}

private const val CLIPBOARD_RETRY_MS = 300L
