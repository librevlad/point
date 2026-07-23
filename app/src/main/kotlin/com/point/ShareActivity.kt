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
import java.io.File

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
                        onIntent = viewModel::onIntent,
                        onSubmitInput = viewModel::submitAmendment,
                        onCancelInput = viewModel::cancelInput,
                        onApplyFavorite = viewModel::applyFavorite,
                        onSaveChain = viewModel::saveCurrentChain,
                        onItem = viewModel::onItem,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.endFlow() // mandatory scratch cleanup
        super.onDestroy()
    }

    private fun handleShare(intent: Intent) {
        val mime = intent.type ?: "application/octet-stream"
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    stream != null -> viewModel.onShared(stream.toString(), mime)

                    intent.hasExtra(Intent.EXTRA_TEXT) -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                        val uri = Uri.fromFile(cacheTextFile(text))
                        viewModel.onShared(uri.toString(), "text/plain")
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!streams.isNullOrEmpty()) viewModel.onSharedMultiple(streams.map { it.toString() })
            }
        }
    }

    private fun cacheTextFile(text: String): File =
        File.createTempFile("shared-", ".txt", cacheDir).apply { writeText(text) }
}
