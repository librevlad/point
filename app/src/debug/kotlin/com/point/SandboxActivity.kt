package com.point

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.point.core.ui.theme.PointTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class SandboxActivity : ComponentActivity() {

    private val viewModel: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.onBack() -> Unit
                viewModel.hasFlow() -> viewModel.endFlow()
                else -> {
                    isEnabled = false
                    this@SandboxActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        setContent {
            PointTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    if (state.frame == null && state.busy == null && state.message == null) {
                        SandboxMenu(onPick = ::start)
                    } else {
                        PointHost(
                            state = state,
                            onBubble = viewModel::onBubble,
                        appIconFor = viewModel::appIcon,
                        onSignIn = viewModel::signIn,
                        onCancelSignIn = viewModel::cancelSignIn,
                        onContinueAfterSignIn = viewModel::dismissSignIn,
                        onRevokeDevice = viewModel::revokeDevice,
                        onSignOut = viewModel::signOut,
                        onCloseDevices = viewModel::closeDevices,
                            onSubmitInput = viewModel::submitAmendment,
                            onCancelInput = viewModel::cancelInput,
                            onItem = viewModel::onItem,
                            onFound = viewModel::onFound,
                            onJumpTo = viewModel::jumpTo,
                            onSendChat = viewModel::sendChatMessage,
                            onCloseChat = viewModel::closeChat,
                            onCancelChat = viewModel::cancelChatMessage,
                            onSaveAiKey = viewModel::saveAiKey,
                            onOpenKeySettings = viewModel::openKeySettings,
                            onCloseKeySettings = viewModel::closeKeySettings,
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
    }

    private fun start(sample: Sample) {
        val file = File(cacheDir, sample.fileName).apply { writeBytes(sample.bytes) }
        viewModel.onShared(Uri.fromFile(file).toString(), sample.mime)
    }
}

private class Sample(
    val label: String,
    val mime: String,
    val fileName: String,
    val bytes: ByteArray,
)

private val SAMPLES: List<Sample> = listOf(

    Sample("Текст", "text/plain", "sample.txt", "Point sandbox — образец текста.\nСсылка: https://example.com".toByteArray()),
    Sample("Картинка", "image/png", "sample.png", Base64.decode(PNG_1x1, Base64.DEFAULT)),
    Sample("PDF", "application/pdf", "sample.pdf", "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".toByteArray()),

    Sample("ZIP", "application/zip", "sample.zip", byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)),
    Sample("Ссылка", "text/uri-list", "sample.uri", "https://example.com".toByteArray()),
)

private const val PNG_1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

@Composable
private fun SandboxMenu(onPick: (Sample) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sandbox — выбери объект", style = MaterialTheme.typography.titleLarge)
        SAMPLES.forEach { sample ->
            Button(onClick = { onPick(sample) }, modifier = Modifier.fillMaxWidth()) {
                Text(sample.label)
            }
        }
    }
}
